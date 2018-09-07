import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Test {
    public static void main(String[] args) {
//        Pattern p = Pattern.compile("^([^\\\\]*)\\\\([^\\\\]*)\\\\(.*)$");
        Pattern p = Pattern.compile("(vertx-[^\\\\]*).*");
        Matcher m = p.matcher("vertx-core\\java\\override\\json.adoc");
        if (m.matches()){
            System.out.println(m.groupCount());
            System.out.println(m.group(1));
//            System.out.println(m.group(2));
//            System.out.println(m.group(3));
        } else {
            System.out.println("not found");
        }
    }
}
