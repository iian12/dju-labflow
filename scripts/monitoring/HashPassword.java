import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** Hash a generated test password from stdin; never print its plaintext. */
class HashPassword {
    public static void main(String[] args) throws Exception {
        String value = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.print(BCrypt.hashpw(value, BCrypt.gensalt()));
    }
}
