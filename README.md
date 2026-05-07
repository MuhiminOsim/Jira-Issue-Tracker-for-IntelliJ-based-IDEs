# Jira Issue Tracker For JetBrain IDEs

Manage your Jira issues directly from your favorite JetBrains IDE.

## Features
- **Browse Issues**: Use JQL queries to find exactly what you need.
- **View Details**: See status, priority, assignee, and full description.
- **Interact**: Add comments and transition issues between statuses.
- **Track Time**: Log work time directly on issues.
- **Browser Integration**: Quick-create issues or open boards in your browser.

## Getting Started

### Prerequisites
- JetBrains IDE (IntelliJ IDEA, Android Studio, etc.)
- Jira Account with API Token

### Configuration
1. Go to **Settings → Tools → Jira Issue Tracker**.
2. Enter your **Jira URL**, **Email**, and **API Token**.
3. (Optional) Set a default JQL query.

## Development
This project is built using the IntelliJ Platform Gradle Plugin.

### Building
```bash
./gradlew buildPlugin
```

### Running in Sandbox
```bash
./gradlew runIde
```

## License
MIT License
