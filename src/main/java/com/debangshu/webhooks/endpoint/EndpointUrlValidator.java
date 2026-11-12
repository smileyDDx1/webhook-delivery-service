package com.debangshu.webhooks.endpoint;

import com.debangshu.webhooks.common.BadRequestException;
import com.debangshu.webhooks.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Checks endpoint URLs before they are saved.
 *
 * The important part is SSRF protection: this service sends HTTP requests to URLs that users type in.
 * Without a check, someone could register "http://169.254.169.254/..." (the cloud metadata service) or
 * "http://localhost:5432" and make the server call things inside our own network. So by default only
 * public addresses are allowed. Local development and the tests turn the check off with
 * app.security.allow-private-urls=true, because their receivers run on localhost.
 */
@Component
public class EndpointUrlValidator {

    /** Separate interface so tests can resolve host names without real DNS. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivateUrls;
    private final HostResolver resolver;

    @Autowired
    public EndpointUrlValidator(AppProperties properties) {
        this(properties.security().allowPrivateUrls(), InetAddress::getAllByName);
    }

    public EndpointUrlValidator(boolean allowPrivateUrls, HostResolver resolver) {
        this.allowPrivateUrls = allowPrivateUrls;
        this.resolver = resolver;
    }

    /** Returns the cleaned up URL, or throws BadRequestException with a reason the user can fix. */
    public String validate(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException("url is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BadRequestException("url must start with http:// or https://");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("url must contain a host name");
        }
        if (uri.getUserInfo() != null) {
            throw new BadRequestException("url must not contain a user name or password");
        }
        if (!allowPrivateUrls) {
            rejectPrivateHost(host);
        }
        return uri.toString();
    }

    private void rejectPrivateHost(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
                || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
            throw new BadRequestException("url must not point to an internal host name (" + host + ")");
        }
        InetAddress[] addresses;
        try {
            // URI keeps the brackets around IPv6 addresses ("[::1]"), the resolver doesn't want them
            addresses = resolver.resolve(lowerHost.replace("[", "").replace("]", ""));
        } catch (UnknownHostException e) {
            throw new BadRequestException("could not resolve the host name " + host);
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new BadRequestException("url points to a private or internal address ("
                        + address.getHostAddress() + "), which is not allowed");
            }
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress()        // 0.0.0.0, ::
                || address.isLoopbackAddress() // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress()// 169.254.0.0/16 (cloud metadata lives here), fe80::/10
                || address.isSiteLocalAddress()// 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0                                     // 0.0.0.0/8
                    || (first == 100 && second >= 64 && second <= 127); // 100.64.0.0/10, carrier-grade NAT
        }
        // fc00::/7, IPv6 "unique local" addresses (the IPv6 version of 10.x / 192.168.x)
        return (bytes[0] & 0xfe) == 0xfc;
    }
}
