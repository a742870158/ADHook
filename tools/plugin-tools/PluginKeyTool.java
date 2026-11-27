import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADHook plugin signing key tool. Single-file, JDK 17+, no third-party dependencies.
 *
 * <pre>
 * java PluginKeyTool.java keygen &lt;private-out.pem&gt; &lt;public-out.txt&gt;
 * java PluginKeyTool.java pubkey &lt;private.pem&gt; &lt;public-out.txt&gt;
 * java PluginKeyTool.java keyid  &lt;public.txt&gt;
 * java PluginKeyTool.java sign   &lt;private.pem&gt; &lt;canonical-file&gt; &lt;out.sig.json&gt; &lt;keyId&gt;
 * java PluginKeyTool.java verify &lt;public.txt&gt; &lt;canonical-file&gt; &lt;sig.json&gt;
 * </pre>
 *
 * <p>Only ECDSA P-256 (secp256r1) with SHA-256 is supported. The private key is
 * written as PKCS#8 PEM, the public key as one base64 line of SPKI DER, the
 * signature as base64 DER inside a JSON envelope. {@code keyId} is the first 16
 * bytes of SHA-256 over the SPKI DER, lowercase hex.
 */
public final class PluginKeyTool {
    private static final String ALGORITHM = "ECDSA_P256_SHA256";
    private static final String CURVE = "secp256r1";
    private static final int KEY_ID_BYTES = 16;
    private static final Set<String> SIG_KEYS = Set.of("schema", "algorithm", "keyId", "signature");

    private PluginKeyTool() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        try {
            switch (args[0]) {
                case "keygen" -> keygen(args);
                case "pubkey" -> pubkey(args);
                case "keyid" -> keyid(args);
                case "sign" -> sign(args);
                case "verify" -> verify(args);
                default -> {
                    usage();
                    throw new ToolException("unknown command: " + args[0]);
                }
            }
        } catch (ToolException e) {
            System.err.println("PluginKeyTool: FAILED: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("PluginKeyTool: FAILED: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("usage: PluginKeyTool <command> ...");
        System.err.println("  keygen <private-out.pem> <public-out.txt>");
        System.err.println("  pubkey <private.pem> <public-out.txt>");
        System.err.println("  keyid  <public.txt>");
        System.err.println("  sign   <private.pem> <canonical-file> <out.sig.json> <keyId>");
        System.err.println("  verify <public.txt> <canonical-file> <sig.json>");
    }

    // ---------------------------------------------------------------- keygen

    private static void keygen(String[] args) throws Exception {
        requireArgs(args, 3);
        Path privateOut = Paths.get(args[1]).toAbsolutePath();
        Path publicOut = Paths.get(args[2]).toAbsolutePath();
        if (Files.exists(privateOut)) {
            throw new ToolException("refusing to overwrite existing private key: " + privateOut);
        }
        if (Files.exists(publicOut)) {
            throw new ToolException("refusing to overwrite existing public key: " + publicOut);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE), new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        byte[] privateDer = pair.getPrivate().getEncoded();
        byte[] publicDer = pair.getPublic().getEncoded();
        if (privateDer == null || publicDer == null) {
            throw new ToolException("JCE did not return encoded EC keys");
        }
        writePrivatePem(privateOut, privateDer);
        createParent(publicOut);
        Files.writeString(publicOut, Base64.getEncoder().encodeToString(publicDer) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        System.out.println("keyId=" + keyId(publicDer));
        System.out.println("fingerprint=" + hex(sha256(publicDer)));
        System.out.println("private=" + privateOut);
        System.out.println("public=" + publicOut);
        System.out.println("curve=" + CURVE + " algorithm=" + ALGORITHM);
    }

    private static void pubkey(String[] args) throws Exception {
        requireArgs(args, 3);
        Path privateIn = Paths.get(args[1]).toAbsolutePath();
        Path publicOut = Paths.get(args[2]).toAbsolutePath();
        ECPrivateKey privateKey = readPrivate(privateIn);
        ECPublicKey publicKey = derivePublic(privateKey);
        byte[] publicDer = publicKey.getEncoded();
        createParent(publicOut);
        Files.writeString(publicOut, Base64.getEncoder().encodeToString(publicDer) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        System.out.println("keyId=" + keyId(publicDer));
        System.out.println("fingerprint=" + hex(sha256(publicDer)));
        System.out.println("public=" + publicOut);
    }

    // ---------------------------------------------------------------- keyid

    private static void keyid(String[] args) throws Exception {
        requireArgs(args, 2);
        ECPublicKey key = readPublic(Paths.get(args[1]).toAbsolutePath());
        System.out.println("keyId=" + keyId(key.getEncoded()));
        System.out.println("fingerprint=" + hex(sha256(key.getEncoded())));
    }

    // ------------------------------------------------------------------ sign

    private static void sign(String[] args) throws Exception {
        requireArgs(args, 5);
        Path privateIn = Paths.get(args[1]).toAbsolutePath();
        Path canonicalFile = Paths.get(args[2]).toAbsolutePath();
        Path signatureOut = Paths.get(args[3]).toAbsolutePath();
        String expectedKeyId = args[4];
        if (!expectedKeyId.matches("[0-9a-f]{32}")) {
            throw new ToolException("keyId must be 32 lowercase hex characters");
        }
        byte[] canonical = readCanonical(canonicalFile);
        ECPrivateKey privateKey = readPrivate(privateIn);
        ECPublicKey publicKey = derivePublic(privateKey);
        String actualKeyId = keyId(publicKey.getEncoded());
        if (!actualKeyId.equals(expectedKeyId)) {
            throw new ToolException("keyId does not match the supplied private key (expected "
                    + expectedKeyId + ", derived " + actualKeyId + ")");
        }
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey, new SecureRandom());
        signer.update(canonical);
        byte[] signature = signer.sign();
        String json = "{\"schema\":1,\"algorithm\":\"" + ALGORITHM + "\",\"keyId\":\""
                + actualKeyId + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signature) + "\"}\n";
        createParent(signatureOut);
        Files.writeString(signatureOut, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        System.out.println("sign: OK keyId=" + actualKeyId + " algorithm=" + ALGORITHM
                + " canonicalBytes=" + canonical.length + " signatureBytes=" + signature.length);
    }

    // ---------------------------------------------------------------- verify

    private static void verify(String[] args) throws Exception {
        requireArgs(args, 4);
        Path publicFile = Paths.get(args[1]).toAbsolutePath();
        Path canonicalFile = Paths.get(args[2]).toAbsolutePath();
        Path signatureFile = Paths.get(args[3]).toAbsolutePath();
        byte[] canonical = readCanonical(canonicalFile);
        ECPublicKey publicKey = readPublic(publicFile);
        String actualKeyId = keyId(publicKey.getEncoded());
        String text = Files.readString(signatureFile, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (ToolException e) {
            throw new ToolException("signature file is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new ToolException("signature JSON must be an object");
        }
        Map<?, ?> object = raw;
        if (!object.keySet().equals(SIG_KEYS)) {
            throw new ToolException("signature JSON must contain exactly " + SIG_KEYS + "; found " + object.keySet());
        }
        Object schema = object.get("schema");
        if (!(schema instanceof Long) || ((Long) schema) != 1L) {
            throw new ToolException("signature schema must be the integer 1");
        }
        Object algorithm = object.get("algorithm");
        if (!ALGORITHM.equals(algorithm)) {
            throw new ToolException("unsupported signature algorithm: " + algorithm + " (only " + ALGORITHM + ")");
        }
        Object keyId = object.get("keyId");
        if (!(keyId instanceof String) || !actualKeyId.equals(keyId)) {
            throw new ToolException("signature keyId does not match the supplied public key (expected "
                    + actualKeyId + ", found " + keyId + ")");
        }
        Object signatureText = object.get("signature");
        if (!(signatureText instanceof String)) {
            throw new ToolException("signature value must be a base64 string");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode((String) signatureText);
        } catch (IllegalArgumentException e) {
            throw new ToolException("signature is not valid base64: " + e.getMessage());
        }
        requireDerEcdsa(signature);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(canonical);
        if (!verifier.verify(signature)) {
            throw new ToolException("signature verification failed");
        }
        System.out.println("verify: OK keyId=" + actualKeyId + " algorithm=" + ALGORITHM);
    }

    private static void requireDerEcdsa(byte[] signature) throws ToolException {
        // DER SEQUENCE { INTEGER r, INTEGER s }, each 1..33 bytes for P-256.
        if (signature.length < 8 || signature.length > 144 || signature[0] != 0x30) {
            throw new ToolException("signature is not a DER ECDSA signature");
        }
        int length = signature[1] & 0xFF;
        int offset = 2;
        if ((length & 0x80) != 0) {
            int bytes = length & 0x7F;
            if (bytes != 1 || signature.length < 4) {
                throw new ToolException("signature DER length is malformed");
            }
            length = signature[2] & 0xFF;
            offset = 3;
        }
        if (offset + length != signature.length) {
            throw new ToolException("signature DER length does not match content");
        }
        offset += derInteger(signature, offset);
        if (offset >= signature.length) {
            throw new ToolException("signature DER is missing the s integer");
        }
        offset += derInteger(signature, offset);
        if (offset != signature.length) {
            throw new ToolException("signature DER has trailing bytes");
        }
    }

    private static int derInteger(byte[] signature, int offset) throws ToolException {
        if (offset + 2 > signature.length || signature[offset] != 0x02) {
            throw new ToolException("signature DER integer tag is malformed");
        }
        int length = signature[offset + 1] & 0xFF;
        if (length < 1 || length > 33 || offset + 2 + length > signature.length) {
            throw new ToolException("signature DER integer length is malformed");
        }
        return 2 + length;
    }

    // --------------------------------------------------------------- helpers

    private static void requireArgs(String[] args, int expected) throws ToolException {
        if (args.length != expected) {
            usage();
            throw new ToolException("wrong argument count for " + args[0] + " (expected "
                    + (expected - 1) + " arguments)");
        }
    }

    private static byte[] readCanonical(Path path) throws ToolException, IOException {
        if (!Files.isRegularFile(path)) {
            throw new ToolException("canonical file is missing: " + path);
        }
        long size = Files.size(path);
        if (size <= 0 || size > 16L * 1024L * 1024L) {
            throw new ToolException("canonical file size is out of range: " + size);
        }
        return Files.readAllBytes(path);
    }

    private static ECPrivateKey readPrivate(Path path) throws ToolException, GeneralSecurityException, IOException {
        if (!Files.isRegularFile(path)) {
            throw new ToolException("private key file is missing: " + path);
        }
        byte[] der = pemDecode(Files.readString(path, StandardCharsets.UTF_8), "PRIVATE KEY");
        PrivateKey key;
        try {
            key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new ToolException("private key is not a PKCS#8 EC key: " + e.getMessage());
        }
        if (!(key instanceof ECPrivateKey privateKey)) {
            throw new ToolException("private key is not an EC private key");
        }
        requireP256(privateKey.getParams());
        return privateKey;
    }

    private static ECPublicKey readPublic(Path path) throws ToolException, GeneralSecurityException, IOException {
        if (!Files.isRegularFile(path)) {
            throw new ToolException("public key file is missing: " + path);
        }
        String text = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || text.contains("BEGIN") || text.contains("----")) {
            throw new ToolException("public key file must be exactly one base64 SPKI line");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new ToolException("public key is not valid base64: " + e.getMessage());
        }
        PublicKey key;
        try {
            key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new ToolException("public key is not an X.509 EC key: " + e.getMessage());
        }
        if (!(key instanceof ECPublicKey publicKey)) {
            throw new ToolException("public key is not an EC public key");
        }
        requireP256(publicKey.getParams());
        return publicKey;
    }

    private static byte[] pemDecode(String text, String label) throws ToolException {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int start = text.indexOf(begin);
        int finish = text.indexOf(end);
        if (start < 0 || finish < 0 || finish <= start) {
            throw new ToolException("missing " + begin + " PEM block");
        }
        if (text.indexOf(begin, start + begin.length()) >= 0) {
            throw new ToolException("multiple " + label + " PEM blocks are not supported");
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start + begin.length(); i < finish; i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                builder.append(c);
            }
        }
        if (builder.length() == 0) {
            throw new ToolException("empty " + label + " PEM block");
        }
        try {
            return Base64.getDecoder().decode(builder.toString());
        } catch (IllegalArgumentException e) {
            throw new ToolException(label + " PEM body is not valid base64: " + e.getMessage());
        }
    }

    private static void writePrivatePem(Path path, byte[] der) throws IOException {
        String base64 = Base64.getEncoder().encodeToString(der);
        StringBuilder builder = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            builder.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        builder.append("-----END PRIVATE KEY-----\n");
        createParent(path);
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and other non-POSIX filesystems: best effort only.
        }
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void requireP256(ECParameterSpec params) throws ToolException, GeneralSecurityException {
        ECParameterSpec expected;
        try {
            AlgorithmParameters algorithm = AlgorithmParameters.getInstance("EC");
            algorithm.init(new ECGenParameterSpec(CURVE));
            expected = algorithm.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ToolException("JCE does not expose " + CURVE + ": " + e.getMessage());
        }
        if (params == null) {
            throw new ToolException("EC key has no curve parameters");
        }
        if (params.getCurve().getField().getFieldSize() != 256
                || !params.getOrder().equals(expected.getOrder())
                || !params.getGenerator().equals(expected.getGenerator())
                || !params.getCurve().getA().equals(expected.getCurve().getA())
                || !params.getCurve().getB().equals(expected.getCurve().getB())) {
            throw new ToolException("EC key must use the P-256/" + CURVE + " curve");
        }
    }

    /**
     * Derives the public half of an EC private key with double-and-add on the
     * prime-field curve. SunEC does not expose this operation directly and the
     * signer must prove the private key matches the advertised keyId.
     */
    private static ECPublicKey derivePublic(ECPrivateKey privateKey)
            throws GeneralSecurityException, ToolException {
        ECParameterSpec params = privateKey.getParams();
        requireP256(params);
        if (privateKey.getS() == null || privateKey.getS().signum() <= 0) {
            throw new ToolException("EC private key has no valid scalar");
        }
        ECPoint point = multiply(params.getGenerator(), privateKey.getS(), params.getCurve());
        if (point.equals(ECPoint.POINT_INFINITY)) {
            throw new ToolException("derived EC public point is the point at infinity");
        }
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(point, params));
    }

    private static ECPoint multiply(ECPoint point, BigInteger scalar, EllipticCurve curve) {
        ECPoint result = ECPoint.POINT_INFINITY;
        for (int bit = scalar.bitLength() - 1; bit >= 0; bit--) {
            result = addPoints(result, result, curve);
            if (scalar.testBit(bit)) {
                result = addPoints(result, point, curve);
            }
        }
        return result;
    }

    private static ECPoint addPoints(ECPoint left, ECPoint right, EllipticCurve curve) {
        if (left.equals(ECPoint.POINT_INFINITY)) {
            return right;
        }
        if (right.equals(ECPoint.POINT_INFINITY)) {
            return left;
        }
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();
        BigInteger x1 = left.getAffineX();
        BigInteger y1 = left.getAffineY();
        BigInteger x2 = right.getAffineX();
        BigInteger y2 = right.getAffineY();
        BigInteger lambda;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) {
                return ECPoint.POINT_INFINITY;
            }
            BigInteger numerator = x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(curve.getA());
            BigInteger denominator = y1.shiftLeft(1).mod(prime);
            lambda = numerator.multiply(denominator.modInverse(prime)).mod(prime);
        } else {
            BigInteger numerator = y2.subtract(y1);
            BigInteger denominator = x2.subtract(x1).mod(prime);
            lambda = numerator.multiply(denominator.modInverse(prime)).mod(prime);
        }
        BigInteger x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(prime);
        BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(prime);
        return new ECPoint(x3, y3);
    }

    private static String keyId(byte[] spkiDer) {
        byte[] digest = sha256(spkiDer);
        byte[] prefix = new byte[KEY_ID_BYTES];
        System.arraycopy(digest, 0, prefix, 0, KEY_ID_BYTES);
        return hex(prefix);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private static final class ToolException extends Exception {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message);
        }
    }

    /**
     * Minimal strict JSON parser for the signature envelope. Supports exactly
     * the JSON value kinds that the envelope can contain; duplicate object keys
     * are rejected instead of silently overwritten.
     */
    private static final class Json {
        private final String text;
        private int index;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) throws ToolException {
            Json parser = new Json(text);
            if (!parser.text.isEmpty() && parser.text.charAt(0) == '\uFEFF') {
                parser.index = 1;
            }
            Object value = parser.value();
            parser.skipWhitespace();
            if (parser.index != parser.text.length()) {
                throw new ToolException("trailing content at offset " + parser.index);
            }
            return value;
        }

        private Object value() throws ToolException {
            skipWhitespace();
            if (index >= text.length()) {
                throw new ToolException("unexpected end of JSON");
            }
            char c = text.charAt(index);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new ToolException("unexpected character '" + c + "' at offset " + index);
                }
            };
        }

        private Map<String, Object> object() throws ToolException {
            Map<String, Object> result = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != '"') {
                    throw new ToolException("expected an object key at offset " + index);
                }
                String key = string();
                if (result.containsKey(key)) {
                    throw new ToolException("duplicate JSON key: " + key);
                }
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != ':') {
                    throw new ToolException("expected ':' at offset " + index);
                }
                index++;
                result.put(key, value());
                skipWhitespace();
                if (index >= text.length()) {
                    throw new ToolException("unterminated JSON object");
                }
                char c = text.charAt(index++);
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw new ToolException("expected ',' or '}' at offset " + (index - 1));
                }
            }
        }

        private List<Object> array() throws ToolException {
            List<Object> result = new ArrayList<>();
            index++;
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(value());
                skipWhitespace();
                if (index >= text.length()) {
                    throw new ToolException("unterminated JSON array");
                }
                char c = text.charAt(index++);
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw new ToolException("expected ',' or ']' at offset " + (index - 1));
                }
            }
        }

        private String string() throws ToolException {
            StringBuilder builder = new StringBuilder();
            index++;
            while (true) {
                if (index >= text.length()) {
                    throw new ToolException("unterminated JSON string");
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        throw new ToolException("unterminated JSON escape");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) {
                                throw new ToolException("truncated JSON unicode escape");
                            }
                            try {
                                builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                            } catch (NumberFormatException e) {
                                throw new ToolException("bad JSON unicode escape");
                            }
                            index += 4;
                        }
                        default -> throw new ToolException("bad JSON escape \\" + escaped);
                    }
                } else if (c < 0x20) {
                    throw new ToolException("raw control character in JSON string");
                } else {
                    builder.append(c);
                }
            }
        }

        private Object number() throws ToolException {
            int start = index;
            if (text.charAt(index) == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && (text.charAt(index) == '.' || text.charAt(index) == 'e'
                    || text.charAt(index) == 'E')) {
                throw new ToolException("non-integer JSON numbers are not accepted in the signature envelope");
            }
            String raw = text.substring(start, index);
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new ToolException("JSON integer out of range: " + raw);
            }
        }

        private Object literal(String literal, Object value) throws ToolException {
            if (!text.startsWith(literal, index)) {
                throw new ToolException("bad JSON literal at offset " + index);
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }
    }
}
