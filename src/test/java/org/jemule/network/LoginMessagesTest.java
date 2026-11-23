package org.jemule.network;

import org.jemule.Main;
import org.jemule.config.ConfigParameter;
import org.jemule.config.KadConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.LoginHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The welcome message and the further login message, shown in the client's server info and editable hot. */
class LoginMessagesTest {
    static final ServerConfig BASE = new ServerConfig(
            14667, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
            null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0);
    static final byte[] USER_HASH = java.util.HexFormat.of().parseHex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf");

    /** The OP_SERVERMESSAGE texts sent for a login with {@code config}, in order. */
    static List<String> serverMessages(ServerConfig config) throws Exception {
        FakeContext ctx = new FakeContext(new DummySocket(InetAddress.getLoopbackAddress(), 51000), config,
                new ClientRegistry(), new FileIndex(null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                loginRequest(USER_HASH, 0, "messages", EMULE_DEFAULT_SRVCAPS)), out);
        List<String> messages = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        while (in.available() > 0) {
            Packet p = Packet.read(in, 65536);
            if (p.opcode() == OpCode.SERVER_MESSAGE.value) {
                ByteBuffer b = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
                byte[] text = new byte[Short.toUnsignedInt(b.getShort())];
                b.get(text);
                messages.add(new String(text, StandardCharsets.UTF_8));
            }
        }
        return messages;
    }

    static String idOf(List<String> messages) {
        String welcome = messages.get(1);
        return welcome.substring(welcome.indexOf("Your ID is: ") + "Your ID is: ".length());
    }

    @Test
    void byDefaultTheWelcomeMessageIsUnchangedAndNothingFollows() throws Exception {
        List<String> messages = serverMessages(BASE);
        assertEquals(2, messages.size(), messages.toString());
        assertEquals("server version " + Main.ESERVER_VERSION, messages.get(0));
        assertTrue(messages.get(1).startsWith("Welcome to JEmuleServer version " + Main.VERSION + "\nYour ID is: "),
                messages.get(1));
        assertTrue(idOf(messages).chars().allMatch(Character::isDigit));
    }

    @Test
    void customMessagesWithTheirPlaceholdersAndLineBreaks() throws Exception {
        ServerConfig config = BASE.withLoginMessages("Hello from {version}\\nYour ID: {id}",
                "Rules: no fakes\\nhttps://example.org");
        List<String> messages = serverMessages(config);
        assertEquals(3, messages.size(), messages.toString());
        assertEquals("server version " + Main.ESERVER_VERSION, messages.get(0), "the version line stays first");
        assertTrue(messages.get(1).matches("Hello from " + java.util.regex.Pattern.quote(Main.VERSION)
                + "\nYour ID: \\d+"), messages.get(1));
        assertEquals("Rules: no fakes\nhttps://example.org", messages.get(2));
    }

    @Test
    void aLoginMessageAloneKeepsTheDefaultWelcome() throws Exception {
        List<String> messages = serverMessages(BASE.withLoginMessages("", "Be nice"));
        assertEquals(3, messages.size());
        assertTrue(messages.get(1).startsWith("Welcome to JEmuleServer version "));
        assertEquals("Be nice", messages.get(2));
    }

    @Test
    void readWrittenAndKeptByTheWithers() {
        Properties props = new Properties();
        props.setProperty("welcomeMessage", " Hi\\nthere ");
        props.setProperty("loginMessage", "Line 1\nLine 2");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals("Hi\\nthere", config.welcomeMessage());
        assertEquals("Line 1\nLine 2", config.loginMessage(), "a real line break from a hand-edited file");
        assertEquals("Hi\\nthere", config.toProperties().get("welcomeMessage"));
        assertEquals("Line 1\nLine 2", config.value(ConfigParameter.LOGIN_MESSAGE));
        ServerConfig changed = config.withPort(4662).withKad(KadConfig.DEFAULT).withServerIdentity("N", "D")
                .withIpv6Sources(true).withObfuscatedFirewallCheck(false).withFileRetentionDays(30);
        assertEquals("Hi\\nthere", changed.welcomeMessage());
        assertEquals("Line 1\nLine 2", changed.loginMessage());
        assertEquals("", ServerConfig.DEFAULT.welcomeMessage());
        assertEquals("", ServerConfig.fromProperties(new Properties()).loginMessage());
    }

    @Test
    void areHotParametersValidated() {
        for (ConfigParameter parameter : new ConfigParameter[]{
                ConfigParameter.WELCOME_MESSAGE, ConfigParameter.LOGIN_MESSAGE}) {
            assertSame(parameter, ConfigParameter.byKey(parameter.key()));
            assertEquals(ConfigParameter.Scope.HOT, parameter.scope());
            parameter.validate("");
            parameter.validate("Welcome to {version}\\nYour ID is {id}, éàü");
            parameter.validate("Line 1\nLine 2");
            assertThrows(IllegalArgumentException.class, () -> parameter.validate("tab\there"));
            assertThrows(IllegalArgumentException.class,
                    () -> parameter.validate("x".repeat(ConfigParameter.MAX_SERVER_MESSAGE_BYTES + 1)));
            parameter.validate("x".repeat(ConfigParameter.MAX_SERVER_MESSAGE_BYTES));
            // eMule would read it as the server's version
            assertThrows(IllegalArgumentException.class, () -> parameter.validate("Hello\\nServer Version 99.9"));
            assertThrows(IllegalArgumentException.class, () -> parameter.validate("server version 1"));
            parameter.validate("The server version is shown above");
        }
    }

    @Test
    void appliesToTheNextLoginWithoutRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        ServerConfig config = new ServerConfig(14662, 1000000, 300, 50, 50000, 100000, 100, 200,
                tempDir.resolve("db").toString(), "", false, 50f, 10, 60, 1800, 120, null);
        Server server = new Server(config, new org.jemule.core.ClientFactory());
        try {
            // As the web panel and the console do it (POST /api/settings, settings <key> <value>)
            var result = server.updateConfig(java.util.Map.of("welcomeMessage", "New welcome {id}",
                    "loginMessage", "New rules"));
            assertTrue(result.success(), result.summary());
            assertTrue(server.pendingRestart().isEmpty(), "applied without restart");
            List<String> messages = serverMessages(server.getConfig());
            assertTrue(messages.get(1).matches("New welcome \\d+"), messages.get(1));
            assertEquals("New rules", messages.get(2));
        } finally {
            server.stop();
        }
    }

    @Test
    void multiLineMessagesFromTheWebPanelTextAreaSurviveTheFileAndReachTheClient(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ServerConfig config = new ServerConfig(14662, 1000000, 300, 50, 50000, 100000, 100, 200,
                tempDir.resolve("db").toString(), "", false, 50f, 10, 60, 1800, 120, null);
        java.nio.file.Path file = tempDir.resolve("server.properties");
        java.nio.file.Files.writeString(file, "# comment\nport=14662\n");
        Server server = new Server(config, new org.jemule.core.ClientFactory());
        server.setConfigFile(new org.jemule.config.ConfigFile(file));
        try {
            assertTrue(ConfigParameter.WELCOME_MESSAGE.multiline());
            assertTrue(ConfigParameter.LOGIN_MESSAGE.multiline());
            assertFalse(ConfigParameter.SERVER_NAME.multiline(), "one line in OP_EMULEINFOACK");
            // A text area posts its line breaks as typed: \n, or \r\n from some browsers
            var result = server.updateConfig(java.util.Map.of(
                    "welcomeMessage", "Welcome {id}\nRules below",
                    "loginMessage", "1. No fakes\r\n2. Be nice\n\nhttps://example.org"));
            assertTrue(result.success(), result.summary());
            List<String> messages = serverMessages(server.getConfig());
            assertTrue(messages.get(1).matches("Welcome \\d+\nRules below"), messages.get(1));
            assertEquals("1. No fakes\n2. Be nice\n\nhttps://example.org", messages.get(2));

            // One escaped line in the file, read back unchanged
            String written = java.nio.file.Files.readString(file);
            assertTrue(written.contains("\nloginMessage=1. No fakes\\r\\n2. Be nice\\n\\nhttps://example.org\n"), written);
            Properties reloaded = new org.jemule.config.ConfigFile(file).load();
            assertEquals("1. No fakes\r\n2. Be nice\n\nhttps://example.org", reloaded.getProperty("loginMessage"));
            assertEquals("Welcome {id}\nRules below", reloaded.getProperty("welcomeMessage"));
        } finally {
            server.stop();
        }
    }
}
