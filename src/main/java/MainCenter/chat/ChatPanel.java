package MainCenter.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A panel that provides chat interface functionality for the terminal.
 * This includes a chat history view and an input field for sending messages.
 */
public class ChatPanel extends VBox {
    private final TextFlow chatHistory;
    private final ScrollPane chatScrollPane;
    private final TextField chatInput;
    private final CheckBox backgroundAnalysisToggle;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
    private final ChatAssistant chatAssistant;

    public ChatPanel() {
        setPadding(new Insets(10));
        setSpacing(10);
        getStyleClass().add("chat-panel");
        
        // Create header
        Label headerLabel = new Label("AI Assistant");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        // Create toggle for background analysis
        backgroundAnalysisToggle = new CheckBox("Background Analysis");
        backgroundAnalysisToggle.setSelected(false);
        backgroundAnalysisToggle.setTooltip(new Tooltip("Enable AI to analyze commands in real-time"));
        
        // Header layout
        HBox header = new HBox(10, headerLabel, new Region(), backgroundAnalysisToggle);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        
        // Chat history view
        chatHistory = new TextFlow();
        chatHistory.setPadding(new Insets(10));
        chatHistory.setLineSpacing(8);
        
        chatScrollPane = new ScrollPane(chatHistory);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setFitToHeight(true);
        chatScrollPane.getStyleClass().add("chat-scroll");
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);
        
        // Input field
        chatInput = new TextField();
        chatInput.setPromptText("Ask a question or request assistance...");
        chatInput.getStyleClass().add("chat-input");
        
        Button sendButton = new Button("Send");
        sendButton.getStyleClass().add("chat-send-button");
        
        HBox inputContainer = new HBox(5, chatInput, sendButton);
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        
        // Initialize chat assistant
        chatAssistant = new ChatAssistant();
        
        // Add all components to the panel
        getChildren().addAll(header, new Separator(), chatScrollPane, inputContainer);
        
        // Event handling
        setupEventHandlers(sendButton);
        
        // Show welcome message
        Platform.runLater(this::displayWelcomeMessage);
    }
    
    private void setupEventHandlers(Button sendButton) {
        // Send button and enter key
        sendButton.setOnAction(e -> sendMessage());
        chatInput.setOnAction(e -> sendMessage());
        
        // Handle background analysis toggle
        backgroundAnalysisToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                addSystemMessage("Background analysis enabled. I'll monitor your commands and provide suggestions.");
            } else {
                addSystemMessage("Background analysis disabled.");
            }
        });
    }
    
    private void sendMessage() {
        String message = chatInput.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        
        // Add user message to chat
        addUserMessage(message);
        
        // Clear input field
        chatInput.clear();
        
        // Get AI response
        processUserMessage(message);
    }
    
    public void addUserMessage(String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        
        Text timeText = new Text("[" + timestamp + "] ");
        timeText.getStyleClass().add("chat-timestamp");
        
        Text nameText = new Text("You: ");
        nameText.getStyleClass().add("chat-user-name");
        nameText.setFill(Color.CORNFLOWERBLUE);
        
        Text messageText = new Text(message + "\n");
        messageText.getStyleClass().add("chat-message");
        
        chatHistory.getChildren().addAll(timeText, nameText, messageText);
        scrollToBottom();
    }
    
    public void addAssistantMessage(String message) {
        String timestamp = LocalDateTime.now().format(timeFormatter);
        
        Text timeText = new Text("[" + timestamp + "] ");
        timeText.getStyleClass().add("chat-timestamp");
        
        Text nameText = new Text("Assistant: ");
        nameText.getStyleClass().add("chat-assistant-name");
        nameText.setFill(Color.LIGHTGREEN);
        
        Text messageText = new Text(message + "\n");
        messageText.getStyleClass().add("chat-message");
        
        chatHistory.getChildren().addAll(timeText, nameText, messageText);
        scrollToBottom();
    }
    
    public void addSystemMessage(String message) {
        Text systemText = new Text("System: " + message + "\n");
        systemText.getStyleClass().add("chat-system-message");
        systemText.setFill(Color.GRAY);
        
        chatHistory.getChildren().add(systemText);
        scrollToBottom();
    }
    
    private void displayWelcomeMessage() {
        addSystemMessage("Welcome to the Terminal AI Assistant!");
        addAssistantMessage("Hello! I'm your terminal assistant. I can help you understand the codebase, suggest commands, or answer questions. What would you like to know?");
    }
    
    private void processUserMessage(String message) {
        // This is where we would connect to an actual AI service
        // For now, we'll simulate a response
        chatAssistant.processMessage(message, response -> {
            Platform.runLater(() -> addAssistantMessage(response));
        });
    }
    
    private void scrollToBottom() {
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }
    
    public void focusInput() {
        Platform.runLater(chatInput::requestFocus);
    }
    
    public boolean isBackgroundAnalysisEnabled() {
        return backgroundAnalysisToggle.isSelected();
    }
    
    /**
     * Analyze a command executed in the terminal and provide feedback if needed
     * @param command The command that was executed
     */
    public void analyzeCommand(String command) {
        if (!isBackgroundAnalysisEnabled()) {
            return;
        }
        
        chatAssistant.analyzeCommand(command, suggestion -> {
            if (suggestion != null && !suggestion.isEmpty()) {
                Platform.runLater(() -> addAssistantMessage("I noticed you used '" + command + "'. " + suggestion));
            }
        });
    }
}
