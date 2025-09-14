package MainCenter.chat;
import com.cohere.api.Cohere;
import com.cohere.api.requests.ChatRequest;
import com.cohere.api.types.ChatMessage;
import com.cohere.api.types.Message;
import com.cohere.api.types.NonStreamedChatResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.List;



/**
 * Client for the Cohere API using the official Java client library
 */
public class CohereClient {
    private final Cohere cohere;
    private final String apiKey;
    private final ExecutorService executor;
    
    /**
     * Create a new Cohere API client
     * 
     * @param apiKey The Cohere API key
     */
    public CohereClient(String apiKey) {
        this.apiKey = apiKey;
        this.cohere = Cohere.builder().token(apiKey).clientName("Termino").build();
        this.executor = Executors.newCachedThreadPool();
    }
    
    /**
     * Generate a chat completion using Cohere's API
     * 
     * @param userMessage The user's message
     * @param context Additional context to provide to the AI (optional)
     * @param responseHandler Callback that receives the AI response
     */
    public void generateChatCompletion(String userMessage, String context, Consumer<String> responseHandler) {
        CompletableFuture.runAsync(() -> {
            try {
                Cohere cohere = Cohere.builder().clientName("snippet").token(apiKey).build();

                NonStreamedChatResponse response =
                    cohere.chat(
                        ChatRequest.builder()
                            // .token(apiKey)
                            .message(userMessage)
                            .chatHistory(
                                List.of(
                                    Message.user(
                                        ChatMessage.builder().message("Who discovered gravity?").build()),
                                    Message.chatbot(
                                        ChatMessage.builder()
                                            .message(
                                                "The man who is widely"
                                                    + " credited with"
                                                    + " discovering gravity"
                                                    + " is Sir Isaac"
                                                    + " Newton")
                                            .build())))
                            .build());
                            System.out.println("Response: " + response.getText());
                            responseHandler.accept(response.getText());
                
            } catch (Exception e) {
                e.printStackTrace();
                responseHandler.accept("I'm sorry, I couldn't generate a response. Error: " + e.getMessage());
            }
        }, executor);
                }
    
    /**
     * Analyze a command using Cohere's API
     * 
     * @param command The command to analyze
     * @param context Command history or other context
     * @param responseHandler Callback to handle the response
     */
    public void analyzeCommand(String command, String context, Consumer<String> responseHandler) {
        CompletableFuture.runAsync(() -> {
            try {
                Cohere cohere = Cohere.builder().clientName("snippet").token(apiKey).build();

                NonStreamedChatResponse response =
                    cohere.chat(
                        ChatRequest.builder()
                            // .token(apiKey)
                            .message(command)
                            .chatHistory(
                                List.of(
                                    Message.user(
                                        ChatMessage.builder().message("Who discovered gravity?").build()),
                                    Message.chatbot(
                                        ChatMessage.builder()
                                            .message(
                                                "The man who is widely"
                                                    + " credited with"
                                                    + " discovering gravity"
                                                    + " is Sir Isaac"
                                                    + " Newton")
                                            .build())))
                            .build());
                            System.out.println("Response: " + response.getText());
                            responseHandler.accept(response.getText());
                
            } catch (Exception e) {
                e.printStackTrace();
                responseHandler.accept("");  // Empty response on error for command analysis
            }
        }, executor);
    }
    
    /**
     * Check if the API key is valid (non-empty)
     * 
     * @return True if the API key appears valid
     */
    public boolean isApiKeyValid() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
    
    /**
     * Shutdown resources used by the client
     */
    public void shutdown() {
        executor.shutdown();
    }
}
