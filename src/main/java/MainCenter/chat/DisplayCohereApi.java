package MainCenter.chat;

import com.cohere.api.requests.ChatRequest;
import java.lang.reflect.Method;
import java.util.Arrays;

public class DisplayCohereApi {
    public static void main(String[] args) {
        try {
            // Print available methods for the ChatRequest.Builder class
            System.out.println("ChatRequest.Builder available methods:");
            System.out.println("=====================================");
            
            Method[] methods = ChatRequest.Builder.class.getDeclaredMethods();
            
            Arrays.sort(methods, (m1, m2) -> m1.getName().compareTo(m2.getName()));
            
            for (Method method : methods) {
                // Skip internal methods
                if (method.getName().contains("$") || method.getName().equals("build")) 
                    continue;
                
                // Format parameters
                String params = Arrays.toString(method.getParameterTypes())
                    .replace("class ", "")
                    .replace("interface ", "")
                    .replace("[", "")
                    .replace("]", "");
                    
                System.out.println(method.getName() + "(" + params + ")");
            }
            
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
