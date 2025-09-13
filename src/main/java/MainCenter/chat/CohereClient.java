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
        this.cohere = Cohere.builder().token(apiKey).build();
        this.executor = Executors.newCachedThreadPool();
        
        // Print all available methods in ChatRequest.Builder
        System.out.println(CohereApiTest.getAllAvailableMethods());
    }
    
    /**
     * Generate a chat completion using Cohere's API
     * 
     * @param userMessage The user's message
     * @param context Additional context to provide to the AI (optional)
     * @param responseHandler Callback to handle the response
     */
    public void generateChatCompletion(String userMessage, String context, Consumer<String> responseHandler) {
        CompletableFuture.supplyAsync(() -> {
            try {
                // Create a chat request builder
                // ChatRequest.Builder requestBuilder = ChatRequest.builder()
                //     .token(apiKey)
                //     .message(userMessage);

                
                
                // if (context != null && !context.isEmpty()) {
                //     requestBuilder.preamble(context);
                // }
                
                // Print available methods in ChatRequest.Builder
                System.out.println("Available methods in ChatRequest.Builder:");
                for (java.lang.reflect.Method method : ChatRequest.Builder.class.getDeclaredMethods()) {
                    System.out.println("- " + method.getName() + "(" + 
                                      java.util.Arrays.toString(method.getParameterTypes())
                                        .replace("class ", "")
                                        .replace("interface ", "") + ")");
                }
                
                // Build the request and send it
                // ChatRequest request = requestBuilder.build();
                // NonStreamedChatResponse response = cohere.chat(request);
                // return response.getText();
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return "I'm sorry, I couldn't generate a response. Error: " + e.getMessage();
            }
        }, executor).thenAccept(responseHandler);
    }
    
    /**
     * Analyze a command using Cohere's API
     * 
     * @param command The command to analyze
     * @param context Command history or other context
     * @param responseHandler Callback to handle the response
     */
    // public void analyzeCommand(String command, String context, Consumer<String> responseHandler) {
    //     CompletableFuture.supplyAsync(() -> {
    //         try {
    //             // Craft a specific prompt for command analysis
    //             String analysisPrompt = "Analyze this command: '" + command + "'. " +
    //                                     "Recent command history: " + context + ". " +
    //                                     "Provide helpful suggestions or tips about this command. " +
    //                                     "Keep the response brief and helpful. If there's nothing " +
    //                                     "particularly noteworthy about the command, just respond with empty text.";
                
    //             // Create a chat request
    //             ChatRequest request = ChatRequest.builder()
    //                 .token(apiKey)
    //                 .message(analysisPrompt)
    //                 .build();
                    
    //             NonStreamedChatResponse response = cohere.chat(request);
    //             return response.getText();
    //         } catch (Exception e) {
    //             e.printStackTrace();
    //             return "";  // Empty response on error for command analysis
    //         }
    //     }, executor).thenAccept(responseHandler);
    // }
    
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
