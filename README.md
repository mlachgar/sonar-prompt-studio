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
- Sonar projects are discovered from `sonar-project.properties` in the repository root or one level below it.
