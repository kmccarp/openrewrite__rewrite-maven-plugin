package org.openrewrite.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.style.Autodetect;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.Generated;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.maven.cache.CompositeMavenPomCache;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.MavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;
import org.openrewrite.maven.internal.RawRepositories;
import org.openrewrite.maven.tree.ProfileActivation;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParsingExecutionContextView;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.internal.ListUtils.map;

// -----------------------------------------------------------------------------------------------------------------
// Notes About Provenance Information:
//
// There are always three markers applied to each source file and there can potentially be up to five provenance markers
// total:
//
// BuildTool     - What build tool was used to compile the source file (This will always be Maven)
// JavaVersion   - What Java version/vendor was used when compiling the source file.
// JavaProject   - For each maven module/sub-module, the same JavaProject will be associated with ALL source files belonging to that module.
//
// Optional:
//
// GitProvenance - If the project exists in the context of a git repository, all source files (for all modules) will have the same GitProvenance.
// JavaSourceSet - All Java source files and all resource files that exist in src/main or src/test will have a JavaSourceSet marker assigned to them.
// -----------------------------------------------------------------------------------------------------------------
public class MavenMojoProjectParser {

    @Nullable
    static MavenPomCache pomCache;

    static JavaTypeCache typeCache = new JavaTypeCache();

    private final Log logger;
    private final Path baseDir;
    private final boolean pomCacheEnabled;
    @Nullable
    private final String pomCacheDirectory;
    private final boolean skipMavenParsing;

    private final BuildTool buildTool;

    private final Collection<String> exclusions;
    private final Collection<String> plainTextMasks;
    private final int sizeThresholdMb;
    private final MavenSession mavenSession;
    private final SettingsDecrypter settingsDecrypter;

    @SuppressWarnings("BooleanParameter")
    public MavenMojoProjectParser(Log logger, Path baseDir, boolean pomCacheEnabled, @Nullable String pomCacheDirectory, RuntimeInformation runtime, boolean skipMavenParsing, Collection<String> exclusions, Collection<String> plainTextMasks, int sizeThresholdMb, MavenSession session, SettingsDecrypter settingsDecrypter) {
        this.logger = logger;
        this.baseDir = baseDir;
        this.pomCacheEnabled = pomCacheEnabled;
        this.pomCacheDirectory = pomCacheDirectory;
        this.skipMavenParsing = skipMavenParsing;
        this.buildTool = new BuildTool(randomId(), BuildTool.Type.Maven, runtime.getMavenVersion());
        this.exclusions = exclusions;
        this.plainTextMasks = plainTextMasks;
        this.sizeThresholdMb = sizeThresholdMb;
        this.mavenSession = session;
        this.settingsDecrypter = settingsDecrypter;
    }

    public List<SourceFile> listSourceFiles(MavenProject mavenProject, List<NamedStyles> styles,
            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

        List<Marker> projectProvenance = generateProvenance(mavenProject);
        List<SourceFile> sourceFiles = new ArrayList<>();
        Set<Path> alreadyParsed = new HashSet<>();

        // First parse the maven project.
        logInfo(mavenProject, "Resolving Poms...");
        Xml.Document maven = parseMaven(mavenProject, projectProvenance, ctx);
        if (maven != null) {
            sourceFiles.add(maven);
            alreadyParsed.add(baseDir.resolve(maven.getSourcePath()));
        }

        Object mavenSourceEncoding = mavenProject.getProperties().get("project.build.sourceEncoding");
        if (mavenSourceEncoding != null) {
            ParsingExecutionContextView.view(ctx).setCharset(Charset.forName(mavenSourceEncoding.toString()));
        }
        JavaParser javaParser = JavaParser.fromJavaVersion()
                .typeCache(typeCache)
                .logCompilationWarningsAndErrors(false)
                .build();
        ResourceParser rp = new ResourceParser(baseDir, logger, exclusions, plainTextMasks, sizeThresholdMb, pathsToOtherMavenProjects(mavenProject));

        sourceFiles.addAll(processMainSources(mavenProject, javaParser, rp, projectProvenance, alreadyParsed, styles, ctx));
        sourceFiles.addAll(processTestSources(mavenProject, javaParser, rp, projectProvenance, alreadyParsed, styles, ctx));
        Collection<PathMatcher> exclusionMatchers = exclusions.stream()
                .map(pattern -> baseDir.getFileSystem().getPathMatcher("glob:" + pattern))
                .collect(toList());
        sourceFiles = ListUtils.map(sourceFiles, sourceFile -> {
            if (sourceFile instanceof J.CompilationUnit) {
                for (PathMatcher excluded : exclusionMatchers) {
                    if (excluded.matches(sourceFile.getSourcePath())) {
                        return null;
                    }
                }
            }
            return sourceFile;
        });
        //Collect any additional files that were not parsed above.
        List<SourceFile> parsedResourceFiles = ListUtils.map(
                rp.parse(mavenProject.getBasedir().toPath(), alreadyParsed),
                addProvenance(baseDir, projectProvenance, null)
        );
        logDebug(mavenProject, "Parsed " + parsedResourceFiles.size() + " additional files found within the project.");
        sourceFiles.addAll(parsedResourceFiles);

        return sourceFiles;
    }

    private List<Marker> generateProvenance(MavenProject mavenProject) {

        String javaRuntimeVersion = System.getProperty("java.runtime.version");
        String javaVendor = System.getProperty("java.vm.vendor");
        String sourceCompatibility = javaRuntimeVersion;
        String targetCompatibility = javaRuntimeVersion;

        String propertiesSourceCompatibility = (String) mavenProject.getProperties().get("maven.compiler.source");
        if (propertiesSourceCompatibility != null) {
            sourceCompatibility = propertiesSourceCompatibility;
        }
        String propertiesTargetCompatibility = (String) mavenProject.getProperties().get("maven.compiler.target");
        if (propertiesTargetCompatibility != null) {
            targetCompatibility = propertiesTargetCompatibility;
        }

        BuildEnvironment buildEnvironment = BuildEnvironment.build(System::getenv);
        return Stream.of(
                buildEnvironment,
                gitProvenance(baseDir, buildEnvironment),
                buildTool,
                new JavaVersion(randomId(), javaRuntimeVersion, javaVendor, sourceCompatibility, targetCompatibility),
                new JavaProject(randomId(), mavenProject.getName(), new JavaProject.Publication(
                        mavenProject.getGroupId(),
                        mavenProject.getArtifactId(),
                        mavenProject.getVersion()
                )))
                .filter(Objects::nonNull)
                .collect(toList());
    }

    private List<SourceFile> processMainSources(
            MavenProject mavenProject,
            JavaParser javaParser,
            ResourceParser resourceParser,
            List<Marker> projectProvenance,
            Set<Path> alreadyParsed,
            List<NamedStyles> styles,
            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

        // Some annotation processors output generated sources to the /target directory. These are added for parsing but
        // should be filtered out of the final SourceFile list.
        List<Path> generatedSourcePaths = listJavaSources(mavenProject.getBuild().getDirectory());
        List<Path> mainJavaSources = Stream.concat(
                generatedSourcePaths.stream(),
                listJavaSources(mavenProject.getBuild().getSourceDirectory()).stream()
        ).collect(toList());

        alreadyParsed.addAll(mainJavaSources);

        logInfo(mavenProject, "Parsing Source Files");
        List<Path> dependencies = mavenProject.getCompileClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());
        javaParser.setClasspath(dependencies);
        javaParser.setSourceSet("main");

        // JavaParser will add SourceSet Markers to any Java SourceFile, so only adding the project provenance info to
        // java source.
        List<J.CompilationUnit> parsedJava = ListUtils.map(applyStyles(javaParser.parse(mainJavaSources, baseDir, ctx), styles),
                addProvenance(baseDir, projectProvenance, generatedSourcePaths));
        logDebug(mavenProject, "Parsed " + parsedJava.size() + " java source files in main scope.");

        //Filter out any generated source files from the returned list, as we do not want to apply the recipe to the
        //generated files.
        Path buildDirectory = baseDir.relativize(Paths.get(mavenProject.getBuild().getDirectory()));
        List<SourceFile> sourceFiles =
                parsedJava.stream().filter(s -> !s.getSourcePath().startsWith(buildDirectory)).collect(Collectors.toList());

        List<SourceFile> parsedResourceFiles = ListUtils.map(
                resourceParser.parse(mavenProject.getBasedir().toPath().resolve("src/main/resources"), alreadyParsed),
                addProvenance(baseDir, ListUtils.concat(projectProvenance, javaParser.getSourceSet(ctx)), null));

        logDebug(mavenProject, "Parsed " + parsedResourceFiles.size() + " resource files in main scope.");
        // Any resources parsed from "main/resources" should also have the main source set added to them.
        sourceFiles.addAll(parsedResourceFiles);
        return sourceFiles.isEmpty() ? Collections.emptyList() : sourceFiles;
    }

    private List<SourceFile> processTestSources(
            MavenProject mavenProject,
            JavaParser javaParser,
            ResourceParser resourceParser,
            List<Marker> projectProvenance,
            Set<Path> alreadyParsed,
            List<NamedStyles> styles,
            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

        List<Path> testDependencies = mavenProject.getTestClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());

        javaParser.setClasspath(testDependencies);
        javaParser.setSourceSet("test");

        // JavaParser will add SourceSet Markers to any Java SourceFile, so only adding the project provenance info to
        // java source.
        List<Path> testJavaSources = listJavaSources(mavenProject.getBuild().getTestSourceDirectory());
        alreadyParsed.addAll(testJavaSources);

        List<J.CompilationUnit> parsedJava = ListUtils.map(
                applyStyles(javaParser.parse(testJavaSources, baseDir, ctx), styles),
                addProvenance(baseDir, projectProvenance, null));

        logDebug(mavenProject, "Parsed " + parsedJava.size() + " java source files in test scope.");
        List<SourceFile> sourceFiles = new ArrayList<>(parsedJava);

        // Any resources parsed from "test/resources" should also have the test source set added to them.
        List<SourceFile> parsedResourceFiles = ListUtils.map(
                resourceParser.parse(mavenProject.getBasedir().toPath().resolve("src/test/resources"), alreadyParsed),
                addProvenance(baseDir, ListUtils.concat(projectProvenance, javaParser.getSourceSet(ctx)), null)
        );
        logDebug(mavenProject, "Parsed " + parsedResourceFiles.size() + " resource files in test scope.");
        sourceFiles.addAll(parsedResourceFiles);
        return sourceFiles.isEmpty() ? Collections.emptyList() : sourceFiles;
    }

    @Nullable
    public Xml.Document parseMaven(MavenProject mavenProject, List<Marker> projectProvenance, ExecutionContext ctx) {
        J.clearCaches();
        if (skipMavenParsing) {
            logger.info("Skipping Maven parsing...");
            return null;
        }

        Set<Path> allPoms = collectPoms(mavenProject, new HashSet<>());
        mavenSession.getProjectDependencyGraph().getUpstreamProjects(mavenProject, true).forEach(p -> collectPoms(p, allPoms));
        MavenParser.Builder mavenParserBuilder = MavenParser.builder().mavenConfig(baseDir.resolve(".mvn/maven.config"));

        MavenSettings settings = buildSettings();
        MavenExecutionContextView mavenExecutionContext = MavenExecutionContextView.view(ctx);
        mavenExecutionContext.setMavenSettings(settings);

        if (pomCacheEnabled) {
            //The default pom cache is enabled as a two-layer cache L1 == in-memory and L2 == RocksDb
            //If the flag is set to false, only the default, in-memory cache is used.
            mavenExecutionContext.setPomCache(getPomCache(pomCacheDirectory, logger));
        }

        List<String> activeProfiles = mavenProject.getActiveProfiles().stream().map(Profile::getId).collect(Collectors.toList());
        if (!activeProfiles.isEmpty()) {
            mavenParserBuilder.activeProfiles(activeProfiles.toArray(new String[]{}));
        }

        List<Xml.Document> mavens = mavenParserBuilder
                .build()
                .parse(allPoms, baseDir, ctx);

        if (logger.isDebugEnabled()) {
            logDebug(mavenProject, "Base Directory : '" + baseDir + "'");
            if (allPoms.isEmpty()) {
                logDebug(mavenProject, "There were no collected pom paths.");
            } else {
                for (Path path : allPoms) {
                    logDebug(mavenProject, "  Collected Pom : '" + path + "'");
                }
            }
            if (mavens.isEmpty()) {
                logDebug(mavenProject, "There were no parsed maven source files.");
            } else {
                for (Xml.Document source : mavens) {
                    logDebug(mavenProject, "  Maven Source : '" + baseDir.resolve(source.getSourcePath()) + "'");
                }
            }
        }

        Xml.Document maven = mavens.stream()
                .filter(o -> pomPath(mavenProject).equals(baseDir.resolve(o.getSourcePath())))
                .findFirst().orElse(null);
        if (maven == null) {
            logError(mavenProject, "Parse resulted in no Maven source files. Maven Project File '" + mavenProject.getFile().toPath() + "'");
            return null;
        }

        for (Marker marker : projectProvenance) {
            maven = maven.withMarkers(maven.getMarkers().addIfAbsent(marker));
        }

        return maven;
    }

    /**
     * Recursively navigate the maven project to collect any poms that are local (on disk)
     *
     * @param project A maven project to examine for any children/parent poms.
     * @param paths A list of paths to poms that have been collected so far.
     * @return All poms associated with the current pom.
     */
    private Set<Path> collectPoms(MavenProject project, Set<Path> paths) {
        paths.add(pomPath(project));

        // children
        if (project.getCollectedProjects() != null) {
            for (MavenProject child : project.getCollectedProjects()) {
                Path path = pomPath(child);
                if (!paths.contains(path)) {
                    collectPoms(child, paths);
                }
            }
        }

        MavenProject parent = project.getParent();
        while (parent != null && parent.getFile() != null) {
            Path path = pomPath(parent);
            if (!paths.contains(path)) {
                collectPoms(parent, paths);
            }
            parent = parent.getParent();
        }
        return paths;
    }

    private static Path pomPath(MavenProject mavenProject) {
        Path pomPath = mavenProject.getFile().toPath();
        // org.codehaus.mojo:flatten-maven-plugin produces a synthetic pom unsuitable for our purposes, use the regular pom instead
        if(pomPath.endsWith(".flattened-pom.xml")) {
            return mavenProject.getBasedir().toPath().resolve("pom.xml");
        }
        return pomPath;
    }

    private static MavenPomCache getPomCache(@Nullable String pomCacheDirectory, Log logger) {
        if (pomCache == null) {
            if (isJvm64Bit()) {
                try {
                    if (pomCacheDirectory == null) {
                        //Default directory in the RocksdbMavenPomCache is ".rewrite-cache"
                        pomCache = new CompositeMavenPomCache(
                                new InMemoryMavenPomCache(),
                                new RocksdbMavenPomCache(Paths.get(System.getProperty("user.home")))
                        );
                    } else {
                        pomCache = new CompositeMavenPomCache(
                                new InMemoryMavenPomCache(),
                                new RocksdbMavenPomCache(Paths.get(pomCacheDirectory))
                        );
                    }
                } catch (Exception e) {
                    logger.warn("Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache");
                    logger.debug(e);
                }
            } else {
                logger.warn("RocksdbMavenPomCache is not supported on 32-bit JVM. falling back to InMemoryMavenPomCache");
            }
        }
        if (pomCache == null) {
            pomCache = new InMemoryMavenPomCache();
        }
        return pomCache;
    }

    private static boolean isJvm64Bit() {
        //It appears most JVM vendors set this property. Only return false if the
        //property has been set AND it is set to 32.
        return !"32".equals(System.getProperty("sun.arch.data.model", "64"));
    }

    private MavenSettings buildSettings() {
        MavenExecutionRequest mer = mavenSession.getRequest();

        MavenSettings.Profiles profiles = new MavenSettings.Profiles();
        profiles.setProfiles(
                mer.getProfiles().stream().map(p -> new MavenSettings.Profile(
                                p.getId(),
                                p.getActivation() == null ? null : new ProfileActivation(
                                        p.getActivation().isActiveByDefault(),
                                        p.getActivation().getJdk(),
                                        p.getActivation().getProperty() == null ? null : new ProfileActivation.Property(
                                                p.getActivation().getProperty().getName(),
                                                p.getActivation().getProperty().getValue()
                                        )
                                ),
                                buildRawRepositories(p.getRepositories())
                        )
                ).collect(toList()));

        MavenSettings.ActiveProfiles activeProfiles = new MavenSettings.ActiveProfiles();
        activeProfiles.setActiveProfiles(mer.getActiveProfiles());

        MavenSettings.Mirrors mirrors = new MavenSettings.Mirrors();
        mirrors.setMirrors(
                mer.getMirrors().stream().map(m -> new MavenSettings.Mirror(
                        m.getId(),
                        m.getUrl(),
                        m.getMirrorOf(),
                        null,
                        null
                )).collect(toList())
        );

        MavenSettings.Servers servers = new MavenSettings.Servers();
        servers.setServers(mer.getServers().stream().map(s -> {
            SettingsDecryptionRequest decryptionRequest = new DefaultSettingsDecryptionRequest(s);
            SettingsDecryptionResult decryptionResult = settingsDecrypter.decrypt(decryptionRequest);
            return new MavenSettings.Server(
                    s.getId(),
                    s.getUsername(),
                    decryptionResult.getServer().getPassword()
            );
        }).collect(toList()));

        return new MavenSettings(mer.getLocalRepositoryPath().toString(), profiles, activeProfiles, mirrors, servers);
    }

    @Nullable
    private static RawRepositories buildRawRepositories(@Nullable List<Repository> repositoriesToMap) {
        if (repositoriesToMap == null) {
            return null;
        }

        RawRepositories rawRepositories = new RawRepositories();
        List<RawRepositories.Repository> transformedRepositories = repositoriesToMap.stream().map(r -> new RawRepositories.Repository(
                r.getId(),
                r.getUrl(),
                r.getReleases() == null ? null : new RawRepositories.ArtifactPolicy(r.getReleases().isEnabled()),
                r.getSnapshots() == null ? null : new RawRepositories.ArtifactPolicy(r.getSnapshots().isEnabled())
        )).collect(toList());
        rawRepositories.setRepositories(transformedRepositories);
        return rawRepositories;
    }

    /**
     * Used to scope `Files.walkFileTree` to the current maven project by skipping the subtrees of other MavenProjects.
     */
    private Set<Path> pathsToOtherMavenProjects(MavenProject mavenProject) {
        return mavenSession.getProjects().stream()
                .filter(o -> o != mavenProject)
                .map(o -> o.getBasedir().toPath())
                .collect(Collectors.toSet());
    }

    private List<J.CompilationUnit> applyStyles(List<J.CompilationUnit> sourceFiles, List<NamedStyles> styles) {
        Autodetect autodetect = Autodetect.detect(sourceFiles);
        NamedStyles merged = NamedStyles.merge(ListUtils.concat(styles, autodetect));
        if(merged == null) {
            return sourceFiles;
        }
        return map(sourceFiles, cu -> cu.withMarkers(cu.getMarkers().add(merged)));
    }

    private static <S extends SourceFile> UnaryOperator<S> addProvenance(Path baseDir, List<Marker> provenance, @Nullable Collection<Path> generatedSources) {
        return s -> {
            for (Marker marker : provenance) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(marker));
            }
            if (generatedSources != null && generatedSources.contains(baseDir.resolve(s.getSourcePath()))) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(new Generated(randomId())));
            }
            return s;
        };
    }

    private static List<Path> listJavaSources(String sourceDirectory) throws MojoExecutionException {
        File sourceDirectoryFile = new File(sourceDirectory);
        if (!sourceDirectoryFile.exists()) {
            return emptyList();
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        try (Stream<Path> files = Files.find(sourceRoot, 16, (f, a) -> !a.isDirectory() && f.toString().endsWith(".java"))) {
            return files.collect(toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list Java source files", e);
        }
    }

    /**
     * Used to reset the type cache.
     */
    public void resetTypeCache() {
        typeCache.clear();
    }

    @Nullable
    private GitProvenance gitProvenance(Path baseDir, @Nullable BuildEnvironment buildEnvironment) {
        try {
            return GitProvenance.fromProjectDirectory(baseDir, buildEnvironment);
        } catch (Exception e) {
            // Logging at a low level as this is unlikely to happen except in non-git projects, where it is expected
            logger.debug("Unable to determine git provenance", e);
        }
        return null;
    }

    private void logError(MavenProject mavenProject, String message) {
        logger.error("Project [" + mavenProject.getName() + "] " + message);
    }

    private void logInfo(MavenProject mavenProject, String message) {
        logger.info("Project [" + mavenProject.getName() + "] " + message);
    }

    private void logDebug(MavenProject mavenProject, String message) {
        logger.debug("Project [" + mavenProject.getName() + "] " + message);
    }
}
