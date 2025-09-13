# Termino - Modern Terminal with AI Assistant

Termino is a modern terminal application built with JavaFX that provides a customizable command-line interface with AI assistance.

## Features

### Terminal Interface
- Custom terminal with modern UI and syntax highlighting
- Modular command system using JSON files
- Command history with up/down arrow navigation
- Customizable appearance with themes

### AI Assistant
- Interactive chat interface for getting help
- Real-time command analysis and recommendations
- Background analysis mode that monitors your commands
- Workflow optimization suggestions based on command patterns

## Running the Application

```
# On Windows
.\gradlew run

# On Linux/Mac
./gradlew run
```

## Command System

Termino uses a modular command system where commands are defined as JSON files in the `commands` directory. To create a custom command, simply add a new JSON file with the following structure:

```json
{
  "name": "mycommand",
  "description": "Description of what the command does",
  "usage": "mycommand <argument>"
}
```

## Using the AI Assistant

### Chat Interface
1. Click the chat button (💬) in the top toolbar to open the chat panel
2. Type your question or request in the input field
3. The AI will respond with relevant information about the terminal, commands, or codebase

### Background Analysis
1. Open the chat panel and enable the "Background Analysis" toggle
2. The AI will monitor your command usage and provide suggestions
3. Receive workflow optimization tips based on your command patterns

## Development

The project is built with Gradle and requires Java 21.

```
# Build the project
.\gradlew build
```

## License

See the LICENSE.txt file for details.
