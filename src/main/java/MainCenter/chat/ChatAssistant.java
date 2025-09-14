package MainCenter.chat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Handles communication with an AI service to process chat messages
 * and provide command analysis.
 */
public class ChatAssistant {
    private final ScheduledExecutorService executor;
    
    // Command history for context tracking
    private List<String> commandHistory;
    
    // Command frequency for recommendations
    private Map<String, Integer> commandFrequency;
    
    // Track user's workflow and patterns
    private List<CommandContext> recentContexts;
    
    // Cohere API client
    private CohereClient cohereClient;
    
    // Hardcoded API key for development (should be moved to settings in production)
    private static final String DEFAULT_API_KEY = "sx4a0IEbAEEb5SxtwTDMIan5llxD97iC4C6d14Lh";
    
    /**
     * Create a new ChatAssistant without API key
     */
    public ChatAssistant() {
        // Use the default API key
        this(DEFAULT_API_KEY);
    }
    
    /**
     * Create a new ChatAssistant with the specified API key
     * 
     * @param apiKey Cohere API key
     */
    public ChatAssistant(String apiKey) {
        executor = Executors.newScheduledThreadPool(1);
        commandHistory = new ArrayList<>();
        commandFrequency = new HashMap<>();
        recentContexts = new ArrayList<>();
        
        // Initialize Cohere client if API key is provided
        if (apiKey != null && !apiKey.isEmpty()) {
            cohereClient = new CohereClient(apiKey);
        }
    }
    
    /**
     * Class to track command execution context
     */
    private static class CommandContext {
        private final String command;
        private final long timestamp;
        private final String previousCommand;
        
        public CommandContext(String command, String previousCommand) {
            this.command = command;
            this.timestamp = System.currentTimeMillis();
            this.previousCommand = previousCommand;
        }
    }
    
    /**
     * Process a user message and get a response from the AI
     * 
     * @param message The user's message
     * @param responseHandler Callback that receives the AI response
     */
    public void processMessage(String message, Consumer<String> responseHandler) {
        CompletableFuture.runAsync(() -> {
            // Always use Cohere API since we now have a default API key
            // Get recent command history to provide context
            String context = getRecentHistoryAsContext();
            
            // Send message to Cohere API
            cohereClient.generateChatCompletion(message, context, responseHandler);
        }, executor);
    }
    
    /**
     * Analyze a terminal command and provide suggestions if relevant
     * 
     * @param command The command to analyze
     * @param suggestionHandler Callback that receives the suggestion
     */
    public void analyzeCommand(String command, Consumer<String> suggestionHandler) {
        // Track command for history and pattern detection
        trackCommand(command);
        
        CompletableFuture.runAsync(() -> {
            try {
                // Always use Cohere with our default API key
                // Get command history for context
                String context = getRecentHistoryAsContext();
                
                // Send to Cohere for analysis
                cohereClient.analyzeCommand(command, context, suggestionHandler);
            } catch (Exception e) {
                // Don't propagate exceptions to the UI thread
                e.printStackTrace();
            }
        }, executor);
    }
    
    /**
     * Track command usage for pattern analysis
     */
    private void trackCommand(String command) {
        commandHistory.add(command);
        
        // Extract base command (before first space)
        String baseCommand = command.split("\\s+")[0].toLowerCase();
        commandFrequency.put(baseCommand, commandFrequency.getOrDefault(baseCommand, 0) + 1);
        
        // Keep context of command sequences
        if (commandHistory.size() > 1) {
            String prevCommand = commandHistory.get(commandHistory.size() - 2);
            recentContexts.add(new CommandContext(command, prevCommand));
        } else {
            recentContexts.add(new CommandContext(command, null));
        }
        
        // Limit history size
        if (commandHistory.size() > 50) {
            commandHistory.remove(0);
        }
        if (recentContexts.size() > 30) {
            recentContexts.remove(0);
        }
    }
    
    /**
     * Analyze user command patterns to identify workflow optimizations
     */
    private void analyzeUserPatterns(Consumer<String> suggestionHandler) {
        // Find most frequently used command
        String mostFrequentCommand = null;
        int maxCount = 0;
        
        for (Map.Entry<String, Integer> entry : commandFrequency.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mostFrequentCommand = entry.getKey();
            }
        }
        
        // Only make suggestions if we have enough data
        if (maxCount > 3 && mostFrequentCommand != null) {
            String suggestion = generatePatternSuggestion(mostFrequentCommand, maxCount);
            if (suggestion != null && !suggestion.isEmpty()) {
                suggestionHandler.accept(suggestion);
            }
        }
        
        // Detect repetitive command sequences
        detectRepetitivePatterns(suggestionHandler);
    }
    
    /**
     * Detect repetitive command sequences that could be automated
     */
    private void detectRepetitivePatterns(Consumer<String> suggestionHandler) {
        if (commandHistory.size() < 6) return;
        
        // Check for repeated sequences of commands (simple pattern recognition)
        List<String> recent = commandHistory.subList(commandHistory.size() - 6, commandHistory.size());
        
        // Check for A-B-A-B pattern
        if (recent.size() >= 4) {
            String a = recent.get(recent.size() - 4).split("\\s+")[0];
            String b = recent.get(recent.size() - 3).split("\\s+")[0];
            String c = recent.get(recent.size() - 2).split("\\s+")[0];
            String d = recent.get(recent.size() - 1).split("\\s+")[0];
            
            if (a.equals(c) && b.equals(d)) {
                suggestionHandler.accept("I notice you're repeating the commands '" + a + "' and '" + b + 
                                        "' in sequence. You might want to create a custom command or script to combine these operations.");
            }
        }
    }
    
    /**
     * Simulates an AI response - replace with actual AI service integration
     */
    private String generateSimulatedResponse(String message) {
        message = message.toLowerCase();
        
        // Include command history context in responses
        StringBuilder contextAwareResponse = new StringBuilder();
        
        // Sample responses based on keywords and context
        if (message.contains("help") || message.contains("what can you do")) {
            contextAwareResponse.append("I can help with:\n- Explaining code in the project\n- Suggesting commands\n- Answering questions about the terminal");
            
            // Add information about recently used commands
            if (!commandHistory.isEmpty()) {
                contextAwareResponse.append("\n\nI see you've been working with ");
                if (commandHistory.size() > 3) {
                    List<String> recentCmds = commandHistory.subList(commandHistory.size() - 3, commandHistory.size());
                    contextAwareResponse.append("these commands recently: " + String.join(", ", recentCmds));
                } else {
                    contextAwareResponse.append("the '" + commandHistory.get(commandHistory.size() - 1) + "' command.");
                }
            }
            
        } else if (message.contains("command") && (message.contains("how") || message.contains("what"))) {
            contextAwareResponse.append("You can run commands like 'echo', 'time', 'clear', and 'exit'. Try typing 'help' in the terminal for a list of available commands.");
            
            // Look for command-specific information in the project
            try {
                File commandsDir = new File("commands");
                if (commandsDir.exists() && commandsDir.isDirectory()) {
                    File[] jsonFiles = commandsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                    if (jsonFiles != null && jsonFiles.length > 0) {
                        contextAwareResponse.append("\n\nI found these custom commands: ");
                        for (int i = 0; i < Math.min(jsonFiles.length, 5); i++) {
                            contextAwareResponse.append(jsonFiles[i].getName().replace(".json", "") + ", ");
                        }
                        if (jsonFiles.length > 5) {
                            contextAwareResponse.append("and " + (jsonFiles.length - 5) + " more.");
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore file system errors
            }
            
        } else if (message.contains("code") || message.contains("project")) {
            contextAwareResponse.append("This project is a custom terminal application built with JavaFX. It has a command registry system that loads commands from JSON files in the 'commands' directory. ");
            contextAwareResponse.append("The application uses a modular design where each command is defined separately and loaded at runtime.");
            
        } else if (message.contains("json") || message.contains("command format")) {
            contextAwareResponse.append("Commands are defined in JSON files. The format includes fields like 'name', 'description', and 'usage'. ");
            contextAwareResponse.append("You can create custom commands by adding new JSON files to the 'commands' directory.");
            
            // Try to provide an example from an actual command file
            try {
                Path examplePath = Paths.get("commands", "echo.json");
                if (Files.exists(examplePath)) {
                    String content = new String(Files.readAllBytes(examplePath));
                    contextAwareResponse.append("\n\nHere's an example from echo.json:\n" + content);
                }
            } catch (Exception e) {
                // Provide fallback example if file read fails
                contextAwareResponse.append("\n\nHere's an example format:\n{");
                contextAwareResponse.append("\n  \"name\": \"mycommand\",");
                contextAwareResponse.append("\n  \"description\": \"Description of what the command does\",");
                contextAwareResponse.append("\n  \"usage\": \"mycommand <argument>\"");
                contextAwareResponse.append("\n}");
            }
            
        } else {
            contextAwareResponse.append("I'll help you with that. To give you more specific assistance, could you provide more details about what you're looking for?");
            
            // Add context from command history if available
            if (!commandHistory.isEmpty()) {
                contextAwareResponse.append("\n\nI noticed you've been working with the terminal. ");
                contextAwareResponse.append("If you're trying to accomplish a specific task, I can suggest relevant commands or approaches.");
            }
        }
        
        return contextAwareResponse.toString();
    }
    
    /**
     * Generates more advanced suggestions based on command context and history
     */
    private String generateAdvancedSuggestion(String command) {
        // Extract base command (before first space)
        String baseCommand = command.split("\\s+")[0].toLowerCase();
        
        // Basic command suggestions
        if (command.equals("help")) {
            return "You can also try 'echo Hello' to see how arguments work.";
        } else if (command.startsWith("echo")) {
            return "You can use quotes for multi-word arguments, like: echo \"Hello World\"";
        } else if (command.equals("time")) {
            return "If you need more time-related functionality, you might want to add a custom 'date' command.";
        } else if (command.equals("clear")) {
            return "You can also use Ctrl+L as a keyboard shortcut to clear the screen.";
        }
        
        // Advanced contextual suggestions based on command history
        if (!commandHistory.isEmpty() && commandHistory.size() > 1) {
            String prevCommand = commandHistory.get(commandHistory.size() - 2);
            String prevBaseCommand = prevCommand.split("\\s+")[0].toLowerCase();
            
            // Detect potential workflows
            if (prevBaseCommand.equals("echo") && baseCommand.equals("time")) {
                return "I notice you're using echo and time together. You might want to create a custom 'timestamp' command that combines text with the current time.";
            }
            
            // Detect repeated commands
            if (baseCommand.equals(prevBaseCommand) && !baseCommand.equals("help")) {
                return "You've used the '" + baseCommand + "' command multiple times in sequence. If you're trying to accomplish something specific, I might be able to suggest a more efficient approach.";
            }
        }
        
        // Return empty if no relevant suggestion
        return "";
    }
    
    /**
     * Generate suggestions based on usage patterns
     */
    private String generatePatternSuggestion(String command, int count) {
        if (command.equals("echo") && count > 5) {
            return "You use 'echo' frequently. Consider creating a custom command or script for your common text outputs.";
        } else if (command.equals("time") && count > 3) {
            return "I notice you check the time often. You might want to add a timestamp display to your terminal prompt.";
        } else if (command.equals("clear") && count > 4) {
            return "You're clearing the screen frequently. Remember you can use Ctrl+L as a shortcut, or you might want to adjust your terminal to show fewer lines.";
        }
        
        return ""; // No suggestion
    }
    
    /**
     * Get recent command history formatted as context for AI
     * @return A string containing recent command history
     */
    private String getRecentHistoryAsContext() {
        if (commandHistory.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder("Recent commands:\n");
        
        // Include up to 10 most recent commands
        int startIndex = Math.max(0, commandHistory.size() - 10);
        for (int i = startIndex; i < commandHistory.size(); i++) {
            context.append("- ").append(commandHistory.get(i)).append("\n");
        }
        
        // Add most frequently used commands
        if (!commandFrequency.isEmpty()) {
            context.append("\nMost used commands:\n");
            commandFrequency.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
                .limit(3)
                .forEach(e -> context.append("- ").append(e.getKey()).append(": ")
                    .append(e.getValue()).append(" times\n"));
        }
        
        return context.toString();
    }
    
    /**
     * Shutdown the executor service and cleanup resources
     */
    public void shutdown() {
        // Clear any stored state
        commandHistory.clear();
        commandFrequency.clear();
        recentContexts.clear();
        
        // Shutdown thread pool
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get insights about the current command usage patterns
     * @return A string with analytics about command usage
     */
    public String getCommandAnalytics() {
        if (commandHistory.isEmpty()) {
            return "No command history available yet.";
        }
        
        StringBuilder analytics = new StringBuilder();
        analytics.append("Command Usage Analytics:\n");
        
        // Most used commands
        analytics.append("Most used commands:\n");
        commandFrequency.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
            .limit(3)
            .forEach(e -> analytics.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append(" times\n"));
        
        // Total commands
        analytics.append("\nTotal commands executed: ").append(commandHistory.size()).append("\n");
        
        return analytics.toString();
    }
}
