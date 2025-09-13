package MainCenter.chat;

import com.cohere.api.Cohere;
import com.cohere.api.requests.ChatRequest;
import com.cohere.api.requests.ChatRequest.Builder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Test class to print available methods in ChatRequest.Builder
 */
public class CohereApiTest {
    
    /**
     * Prints all available methods and fields in the ChatRequest.Builder class
     */
    public static String getAllAvailableMethods() {
        StringBuilder sb = new StringBuilder();
        sb.append("ChatRequest.Builder available methods:\n");
        sb.append("=====================================\n");
        
        try {
            for (Method method : Builder.class.getDeclaredMethods()) {
                // Format the method parameters for better readability
                String params = Arrays.toString(method.getParameterTypes())
                    .replace("class ", "")
                    .replace("interface ", "")
                    .replace("[", "")
                    .replace("]", "");
                
                // Print method name and parameters
                sb.append(method.getReturnType().getSimpleName() + " " + 
                          method.getName() + "(" + params + ")\n");
            }
            
            // Also print available fields if any
            sb.append("\nChatRequest.Builder available fields:\n");
            sb.append("=====================================\n");
            for (Field field : Builder.class.getDeclaredFields()) {
                sb.append(field.getType().getSimpleName() + " " + field.getName() + "\n");
            }
            
            // Print available methods in ChatRequest
            sb.append("\nChatRequest available methods:\n");
            sb.append("=====================================\n");
            for (Method method : ChatRequest.class.getDeclaredMethods()) {
                String params = Arrays.toString(method.getParameterTypes())
                    .replace("class ", "")
                    .replace("interface ", "")
                    .replace("[", "")
                    .replace("]", "");
                
                sb.append(method.getReturnType().getSimpleName() + " " + 
                          method.getName() + "(" + params + ")\n");
            }
            
            // Print additional helper information if needed
            sb.append("\nExample usage:\n");
            sb.append("ChatRequest request = ChatRequest.builder()\n");
            sb.append("    .token(apiKey)\n");
            sb.append("    .message(message)\n");
            sb.append("    .preamble(context) // Optional\n");
            sb.append("    .temperature(0.7f) // Optional\n");
            sb.append("    .build();\n");
        } catch (Exception e) {
            sb.append("Error accessing reflection info: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
        
        return sb.toString();
    }
    
    public static void main(String[] args) {
        System.out.println(getAllAvailableMethods());
    }
}
