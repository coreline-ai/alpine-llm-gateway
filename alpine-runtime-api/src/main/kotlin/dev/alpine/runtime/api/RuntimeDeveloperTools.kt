package dev.alpine.runtime.api

/**
 * A fixed, inspectable first-run check for an allowlisted developer tool.
 *
 * The command is a direct argv request rather than a shell fragment. Hosts can render its label
 * and dispatch it after a user has intentionally installed the matching profile without exposing
 * arbitrary command execution through a package-management control.
 */
data class RuntimeDeveloperToolProfile(
    val id: String,
    val label: String,
    val packages: List<String>,
    val smokeRequest: RuntimeCommandRequest,
) {
    init {
        require(ID_PATTERN.matches(id)) { "invalid developer tool profile id" }
        require(label.isNotBlank()) { "developer tool label must not be blank" }
        require(packages.isNotEmpty()) { "developer tool profile must declare packages" }
        require(packages.size <= RuntimePackageInstallRequest.MAX_PACKAGES_PER_REQUEST) {
            "too many developer tool packages"
        }
        require(packages.distinct().size == packages.size) { "duplicate developer tool packages" }
        require(packages.all(DEVELOPER_TOOL_PACKAGE_NAME::matches)) {
            "invalid developer tool package"
        }
        require(smokeRequest.executable !in SHELL_EXECUTABLES) { "developer tool smoke may not invoke a shell" }
        require(smokeRequest.environment.isEmpty()) { "developer tool smoke may not inject environment values" }
        require(smokeRequest.arguments.isNotEmpty() && smokeRequest.arguments.size <= 4) {
            "developer tool smoke arguments must be a small fixed argv"
        }
        require(smokeRequest.arguments.all(SAFE_ARGUMENT::matches)) {
            "developer tool smoke arguments contain unsupported characters"
        }
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9-]{0,31}")
        private val DEVELOPER_TOOL_PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+_.-]{0,127}")
        private val SAFE_ARGUMENT = Regex("[A-Za-z0-9_./:=@+\\-]+")
        private val SHELL_EXECUTABLES = setOf("/bin/sh", "/bin/ash", "/usr/bin/env")
    }
}

/**
 * Conservative default profiles for Alpine developer tooling. Each request has a fixed absolute
 * executable and a version-only argument, so it cannot contain a workspace path, secret, shell
 * pipeline, or user-provided text.
 */
val DefaultRuntimeDeveloperToolProfiles = listOf(
    RuntimeDeveloperToolProfile(
        id = "python",
        label = "Python",
        packages = listOf("python3", "py3-pip"),
        smokeRequest = RuntimeCommandRequest("/usr/bin/python3", listOf("--version")),
    ),
    RuntimeDeveloperToolProfile(
        id = "git",
        label = "Git",
        packages = listOf("git"),
        smokeRequest = RuntimeCommandRequest("/usr/bin/git", listOf("--version")),
    ),
    RuntimeDeveloperToolProfile(
        id = "ssh",
        label = "SSH",
        packages = listOf("openssh-client"),
        smokeRequest = RuntimeCommandRequest("/usr/bin/ssh", listOf("-V")),
    ),
    RuntimeDeveloperToolProfile(
        id = "node",
        label = "Node",
        packages = listOf("nodejs", "npm"),
        smokeRequest = RuntimeCommandRequest("/usr/bin/node", listOf("--version")),
    ),
)
