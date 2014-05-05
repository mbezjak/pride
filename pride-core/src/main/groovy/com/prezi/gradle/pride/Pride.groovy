package com.prezi.gradle.pride

import com.prezi.gradle.pride.internal.LoggerOutputStream
import com.prezi.gradle.pride.vcs.Vcs
import com.prezi.gradle.pride.vcs.VcsManager
import org.apache.commons.configuration.Configuration
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.gradle.GradleBuild
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Created by lptr on 10/04/14.
 */
class Pride {
	private static final Logger log = LoggerFactory.getLogger(Pride)

	private static final ThreadLocal<GradleConnector> gradleConnector = new ThreadLocal<>() {
		@Override
		protected GradleConnector initialValue() {
			log.info "Starting Gradle connector"
			return GradleConnector.newConnector()
		}
	}

	public static final String PRIDE_CONFIG_DIRECTORY = ".pride"
	public static final String PRIDE_MODULES_FILE = "modules"
	public static final String PRIDE_VERSION_FILE = "version"
	public static final String GRADLE_SETTINGS_FILE = "settings.gradle"
	public static final String GRADLE_BUILD_FILE = "build.gradle"

	private static final String DO_NOT_MODIFY_WARNING = """//
// DO NOT MODIFY -- This file is generated by Pride, and will be
// overwritten whenever the pride itself is changed.
//
"""

	public final File rootDirectory
	private final File configDirectory
	private final VcsManager vcsManager
	@SuppressWarnings("GrFinalVariableAccess")
	private final SortedMap<String, Module> modules

	public static Pride lookupPride(File directory, Configuration configuration, VcsManager vcsManager) {
		if (containsPride(directory)) {
			return new Pride(directory, configuration, vcsManager)
		} else {
			def parent = directory.parentFile
			if (parent) {
				return lookupPride(parent, configuration, vcsManager)
			} else {
				return null
			}
		}
	}

	public static Pride getPride(File directory, Configuration configuration, VcsManager vcsManager) {
		def pride = lookupPride(directory, configuration, vcsManager)
		if (pride == null) {
			throw new PrideException("No pride found in ${directory}")
		}
		return pride
	}

	public static boolean containsPride(File directory) {
		def versionFile = new File(new File(directory, PRIDE_CONFIG_DIRECTORY), PRIDE_VERSION_FILE)
		def result = versionFile.exists() && versionFile.text == "0\n"
		log.debug "Directory ${directory} contains a pride: ${result}"
		return result
	}

	public static Pride create(File prideDirectory, Configuration configuration, VcsManager vcsManager) {
		log.info "Initializing ${prideDirectory}"
		prideDirectory.mkdirs()

		def configDirectory = getPrideConfigDirectory(prideDirectory)
		configDirectory.delete() || configDirectory.deleteDir()
		configDirectory.mkdirs()
		getPrideVersionFile(configDirectory) << "0\n"
		getPrideModulesFile(configDirectory).createNewFile()

		def pride = getPride(prideDirectory, configuration, vcsManager)
		pride.reinitialize()
		return pride
	}

	private Pride(File rootDirectory, Configuration configuration, VcsManager vcsManager) {
		this.rootDirectory = rootDirectory
		this.vcsManager = vcsManager
		this.configDirectory = getPrideConfigDirectory(rootDirectory)
		if (!configDirectory.directory) {
			throw new PrideException("No pride in directory \"${rootDirectory}\"")
		}
		this.modules = loadModules(rootDirectory, getPrideModulesFile(configDirectory), configuration, vcsManager)
	}

	public void reinitialize() {
		def buildFile = gradleBuildFile
		buildFile.delete()
		buildFile << DO_NOT_MODIFY_WARNING << getClass().getResourceAsStream("/build.gradle")

		def settingsFile = gradleSettingsFile
		settingsFile.delete()
		settingsFile << DO_NOT_MODIFY_WARNING

		modules.values().each { Module module ->
			def moduleDirectory = new File(rootDirectory, module.name)
			if (isValidModuleDirectory(moduleDirectory)) {
				initializeModule(moduleDirectory, settingsFile)
			}
		}
	}

	private void initializeModule(File moduleDirectory, File settingsFile) {
		def connection = gradleConnector.get().forProjectDirectory(moduleDirectory).connect()
		try {
			def relativePath = rootDirectory.toURI().relativize(moduleDirectory.toURI()).toString()

			// Load the model for the build
			def builder = connection.model(GradleBuild)
			// Redirect output to loggers
			// Won't work until http://issues.gradle.org/browse/GRADLE-2687
			builder.standardError = new LoggerOutputStream({ log.error("{}", it) })
			builder.standardOutput = new LoggerOutputStream({ log.info("{}", it) })
			builder.withArguments("-q")
			GradleBuild build = builder.get()

			// Merge settings
			settingsFile << "\n// Settings from project in directory /${relativePath}\n\n"
			build.projects.each { project ->
				if (project == build.rootProject) {
					settingsFile << "include '${build.rootProject.name}'\n"
					settingsFile << "project(':${build.rootProject.name}').projectDir = file('${moduleDirectory.name}')\n"
				} else {
					settingsFile << "include '${build.rootProject.name}${project.path}'\n"
				}
			}
		} catch (Exception ex) {
			throw new PrideException("Could not parse module in ${moduleDirectory}: ${ex}", ex)
		} finally {
			// Clean up
			connection.close()
		}
	}

	public Collection<Module> getModules() {
		return modules.values().asImmutable()
	}

	public Module addModule(String name, Vcs vcs) {
		def module = new Module(name, vcs)
		modules.put name, module
		return module
	}

	public void removeModule(String name) {
		def moduleDir = getModuleDirectory(name)
		log.info "Removing ${name} from ${moduleDir}"
		modules.remove(name)
		// Make sure we remove symlinks and directories alike
		moduleDir.delete() || moduleDir.deleteDir()
	}

	public boolean hasModule(String name) {
		return modules.containsKey(name)
	}

	public Module getModule(String name) {
		if (!modules.containsKey(name)) {
			throw new PrideException("No module with name ${name}")
		}
		return modules.get(name)
	}

	public File getModuleDirectory(String name) {
		// Do this round-trip to make sure we have the module
		def module = getModule(name)
		return new File(rootDirectory, module.name)
	}

	public void save() {
		def modulesFile = getPrideModulesFile(configDirectory)
		modulesFile.delete()
		modulesFile.createNewFile()
		modules.values().each { Module module ->
			modulesFile << module.vcs.type + "|" + module.name + "\n"
		}
	}

	private static SortedMap<String, Module> loadModules(File rootDirectory, File modulesFile, Configuration configuration, VcsManager vcsManager) {
		if (!modulesFile.exists()) {
			throw new PrideException("Cannot find modules file at ${modulesFile}")
		}
		List<Module> modules = modulesFile.readLines().collectMany() { String line ->
			def moduleLine = line.trim()
			if (moduleLine.empty || moduleLine.startsWith("#")) {
				return []
			}

			String moduleName
			String vcsType
			def matcher = moduleLine =~ /(.*)?\|(.*)/
			if (matcher) {
				vcsType = matcher[0][1]
				moduleName = matcher[0][2]
			} else {
				// Default to git for backwards compatibility
				vcsType = "git"
				moduleName = moduleLine
			}

			def moduleDir = new File(rootDirectory, moduleName)
			if (!moduleDir.directory) {
				throw new PrideException("Module \"${moduleName}\" is missing")
			}
			if (!isValidModuleDirectory(moduleDir)) {
				throw new PrideException("No module found in \"${moduleDir}\"")
			}

			log.debug("Found {} module {}", vcsType, moduleName)
			return [ new Module(moduleName, vcsManager.getVcs(vcsType, configuration)) ]
		}
		def modulesMap = new TreeMap<String, Module>()
		modules.collectEntries(modulesMap) { [it.name, it] }
		return modulesMap
	}

	public static boolean isValidModuleDirectory(File dir) {
		return !dir.name.startsWith(".") &&
				dir.list().contains(GRADLE_BUILD_FILE)
	}

	private File getGradleSettingsFile() {
		return new File(rootDirectory, GRADLE_SETTINGS_FILE)
	}

	private File getGradleBuildFile() {
		return new File(rootDirectory, GRADLE_BUILD_FILE)
	}

	private static File getPrideConfigDirectory(File prideDirectory) {
		return new File(prideDirectory, PRIDE_CONFIG_DIRECTORY)
	}

	private static File getPrideModulesFile(File configDirectory) {
		return new File(configDirectory, PRIDE_MODULES_FILE)
	}

	private static File getPrideVersionFile(File configDirectory) {
		return new File(configDirectory, PRIDE_VERSION_FILE)
	}
}