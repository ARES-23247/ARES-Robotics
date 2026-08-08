import java.lang.reflect.Method;

public class InspectLimelight {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("com.qualcomm.hardware.limelightvision.LLResult");
        for (Method m : clazz.getMethods()) {
            if (m.getName().toLowerCase().contains("latency")) {
                System.out.println("LLResult method: " + m.getName());
            }
        }
        
        Class<?> fClazz = Class.forName("com.qualcomm.hardware.limelightvision.LLResultTypes");
        for (Method m : fClazz.getMethods()) {
            if (m.getName().toLowerCase().contains("ambiguity")) {
                System.out.println("Fiducial method: " + m.getName());
            }
        }
    }
}
