package com.debangshu.webhooks.endpoint;

import com.debangshu.webhooks.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointUrlValidatorTest {

    /** Fake DNS: a few known names, IP literals are parsed without any lookup. */
    private static InetAddress[] fakeDns(String host) throws UnknownHostException {
        return switch (host) {
            case "hooks.example.com" -> new InetAddress[]{InetAddress.getByName("93.184.215.14")};
            case "sneaky.example.com" -> new InetAddress[]{InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.5")};
            case "nowhere.example.com" -> throw new UnknownHostException(host);
            default -> new InetAddress[]{InetAddress.getByName(host)};
        };
    }

    private final EndpointUrlValidator strict = new EndpointUrlValidator(false, EndpointUrlValidatorTest::fakeDns);
    private final EndpointUrlValidator relaxed = new EndpointUrlValidator(true, EndpointUrlValidatorTest::fakeDns);

    @Test
    void acceptsPublicHttpsAndHttpUrls() {
        assertThat(strict.validate("https://hooks.example.com/webhooks?team=1")).isEqualTo("https://hooks.example.com/webhooks?team=1");
        assertThat(strict.validate("  http://hooks.example.com/in  ")).isEqualTo("http://hooks.example.com/in");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://hooks.example.com/x", "file:///etc/passwd", "hooks.example.com/no-scheme", "javascript:alert(1)"})
    void rejectsOtherSchemes(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("http:// or https://");
    }

    @Test
    void rejectsUrlsWithoutHostOrWithCredentials() {
        assertThatThrownBy(() -> strict.validate("http:///path")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> strict.validate("https://user:pass@hooks.example.com/"))
                .hasMessageContaining("user name or password");
        assertThatThrownBy(() -> strict.validate("https://exa mple.com")).hasMessageContaining("not a valid URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/hook",       // loopback
            "http://10.1.2.3/hook",             // private
            "http://172.16.0.10/hook",          // private
            "http://192.168.1.20/hook",         // private
            "http://169.254.169.254/latest/meta-data/", // cloud metadata service
            "http://0.0.0.0/hook",
            "http://100.64.0.1/hook",           // carrier-grade NAT
            "http://[::1]/hook",                // IPv6 loopback
            "http://[fd12:3456::1]/hook"        // IPv6 unique local
    })
    void rejectsPrivateAndInternalAddresses(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("private or internal address");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9090/hook", "http://api.localhost/hook", "http://db.internal/hook", "http://printer.local/"})
    void rejectsInternalHostNames(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("internal host name");
    }

    @Test
    void rejectsHostNamesThatResolveToAPrivateAddress() {
        // one public and one private address: still rejected
        assertThatThrownBy(() -> strict.validate("https://sneaky.example.com/hook"))
                .hasMessageContaining("10.0.0.5");
    }

    @Test
    void rejectsHostNamesThatDoNotResolve() {
        assertThatThrownBy(() -> strict.validate("https://nowhere.example.com/hook"))
                .hasMessageContaining("could not resolve");
    }

    @Test
    void privateUrlsAreAllowedWhenTheCheckIsTurnedOff() {
        assertThat(relaxed.validate("http://localhost:9090/hook")).isEqualTo("http://localhost:9090/hook");
        assertThat(relaxed.validate("http://10.1.2.3/hook")).isEqualTo("http://10.1.2.3/hook");
    }
}
