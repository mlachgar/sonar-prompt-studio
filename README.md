# Sonar Prompt Studio

Native IntelliJ IDEA plugin for SonarQube Cloud and self-hosted SonarQube Server review plus remediation prompt generation for Codex, Claude Code, and Qwen Code.

## Run

```bash
./gradlew runIde
```

## Build

```bash
./gradlew build
```

## Notes

- Configure profiles in `Settings | Tools | Sonar Prompt Studio`.
- Tokens are stored in JetBrains secure credential storage. If no secure token exists for a profile, local `.env` `SONAR_TOKEN` is used as fallback.
- Project discovery currently reads `sonar-project.properties`, `build.gradle`, and `build.gradle.kts` in the repository root or one level below it.
- If no project is discovered, add a `sonar-project.properties` file at the project root. This is the required fallback format for manual setup.
- Other project kinds often declare `sonar.projectKey` and `sonar.organization` in different places that are not discovered yet:
  - Maven projects: `pom.xml`, usually via `<properties>` entries or scanner plugin configuration.
  - .NET projects: `SonarScanner for .NET` command arguments such as `/k:<project-key>` and `/o:<organization>`, or CI pipeline variables.
  - CLI-driven projects in any language: scanner arguments such as `-Dsonar.projectKey=...` and `-Dsonar.organization=...`.
  - CI-managed projects: GitHub Actions, GitLab CI, Jenkins, Azure DevOps, and similar pipelines often keep these values in workflow files, job parameters, or environment variables.
  - Containerized builds: `Dockerfile`, `docker-compose.yml`, or wrapper scripts may pass Sonar properties as environment variables or scanner flags.
