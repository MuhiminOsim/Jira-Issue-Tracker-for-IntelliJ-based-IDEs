# 🚀 Jira Issue Tracker for JetBrains IDEs

[![Version](https://img.shields.io/badge/version-1.2.0-blue.svg)](https://github.com/MuhiminOsim/Jira-Issue-Tracker-for-IntelliJ-based-IDEs)
[![Platform](https://img.shields.io/badge/platform-JetBrains-orange.svg)](https://plugins.jetbrains.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Streamline your workflow by managing Jira issues directly within your development environment.**

![Jira Plugin Mockup](/Users/osim/.gemini/antigravity/brain/bf120266-8296-41a7-a040-e8723ef7c272/jira_plugin_mockup_1778429697628.png)

---

## ✨ Overview

Jira Issue Tracker is a high-performance, feature-rich plugin designed for developers who want to minimize context switching. Whether you're tracking progress, logging time, or creating new tasks, everything is just a double-click away.

## 🌟 Key Features

### 🛠 Powerful Issue Management
- **Interactive Sprint Board**: A native Agile board with status columns and real-time synchronization.
- **Drag-and-Drop Workflow**: Move issue cards between columns to instantly transition Jira statuses.
- **Smart Browsing**: Search and filter issues using custom JQL queries.
- **Detailed View**: Access full descriptions, status, priority, and assignee information in a dedicated detail dialog.
- **Seamless Interaction**: Add comments, transition statuses, and update issue summaries/descriptions directly.

### ⚡ Optimized UX
- **Responsive Toolbar**: Action buttons automatically hide in narrow views to prioritize critical search and space selection.
- **Double-Click Integration**: Double-click any issue in the list or board to open the detailed view immediately.
- **Session Persistence**: The plugin remembers your last selected workspace/project across IDE restarts and indexing.
- **Modern Aesthetics**: A premium, theme-aware interface with rounded cards, badges, and high-contrast visuals.

### ⏱ Time Tracking & Productivity
- **Work Logging**: Log your time spent on tasks without leaving the IDE.
- **Browser Shortcuts**: One-click to open issues or boards in your default browser for deep dives.

---

## 🚀 Getting Started

### Prerequisites
- A JetBrains IDE (IntelliJ IDEA, WebStorm, Android Studio, etc.) version 2023.2 or later.
- A Jira Cloud or On-Premise account.
- A **Jira API Token** (for Jira Cloud, generate one [here](https://id.atlassian.com/manage-profile/security/api-tokens)).

### Installation
1. Open your JetBrains IDE.
2. Go to **Settings** (or **Preferences** on macOS) → **Plugins**.
3. Select **Marketplace** and search for "Jira Issue Tracker".
4. Click **Install** and restart your IDE if prompted.

### Configuration
1. Navigate to **Settings → Tools → Jira Issue Tracker**.
2. Enter your **Jira URL** (e.g., `https://your-domain.atlassian.net`).
3. Enter your **Account Email**.
4. Paste your **Jira API Token**.
5. Click **Test Connection** to verify your setup.
6. (Optional) Customize your default JQL query (e.g., `statusCategory != Done ORDER BY updated DESC`).

---

## 🛠 Development

This plugin is built using **Kotlin** and the **IntelliJ Platform Gradle Plugin**.

### Build Instructions
```bash
# Clone the repository
git clone https://github.com/MuhiminOsim/Jira-Issue-Tracker-for-IntelliJ-based-IDEs.git

# Build the plugin
./gradlew buildPlugin

# Run a sandbox instance of IntelliJ with the plugin installed
./gradlew runIde
```

---

## 🤝 Contributing

Contributions are welcome! If you have suggestions, bug reports, or want to add new features:
1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/amazing-feature`).
3. Commit your changes (`git commit -m 'Add some amazing feature'`).
4. Push to the branch (`git push origin feature/amazing-feature`).
5. Open a Pull Request.

---

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  Made with ❤️ by <a href="https://github.com/MuhiminOsim">Muhimin Osim</a>
</p>
