import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HTTPParser {

    private static final Logger LOGGER = Logger.getLogger(HTTPParser.class.getName());

    public static final String HTTP_LINE_SEPARATOR = "\r\n";
    public static final String HEADER_SEPARATOR = ": ";
    public static final String REQUEST_METHOD = "method";
    public static final String REQUEST_RESOURCE = "resource";
    public static final String REQUEST_HTTP_VERSION = "version";
    public static final String HEADER_DATE = "Date";
    public static final String HEADER_HOST = "Host";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String AUTHORIZATION_PREFIX = "Basic ";
    public static final String HTTP_1_1 = "HTTP/1.1";
    public static final String KEY = "key";
    public static final String VALUE = "value";

    public static final String RESPONSE_LINE = HTTP_1_1 + " %d %s";
    public static final String RESPONSE_BODY_GET = "Greetings %s!";
    public static final String RESPONSE_BODY_POST = RESPONSE_BODY_GET + " You have successfully created %s with %s.";
    public static final String POST_RESPONSE_ITEMS_FORMAT = "%s - %s";
    public static final String POST_RESPONSE_ITEMS_DELIMITER = ", ";

    public static final Pattern REQUEST_LINE_PATTERN = Pattern.compile(
            String.format("^(?<%s>[A-Z]{3,7}) (?<%s>/[a-zA-Z0-9/]+) (?<%s>HTTP/[0-9.]+)$",
                    REQUEST_METHOD, REQUEST_RESOURCE, REQUEST_HTTP_VERSION));

    public static final Pattern BODY_PARAMS_PATTERN = Pattern.compile(
            String.format("&?(?<%s>[A-Za-z0-9]+)=(?<%s>[A-Za-z0-9]+)",
                    KEY, VALUE));

    public static final Pattern HEADER_PATTERN = Pattern.compile(
            String.format("^(?<%s>[^ :]+)%s(?<%s>.+)$",
                    KEY, HEADER_SEPARATOR, VALUE));

    public static final Pattern URLS_PATTERN = Pattern.compile("/[^ ]+");

    public static final Set<String> RESPONSE_HEADERS =
            Set.of(HEADER_DATE, HEADER_HOST, HEADER_CONTENT_TYPE);

    public static final Set<String> VALID_HEADERS =
            Set.of(HEADER_DATE, HEADER_HOST, HEADER_CONTENT_TYPE, HEADER_AUTHORIZATION);

    public static final Set<String> HTTP_VERSIONS = Set.of(HTTP_1_1);

    private static final Set<String> HTTP_METHODS = Stream.of(HttpMethod.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    public static void main(String[] args) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            Set<String> urls = parseUrls(reader);
            Map<String, String> request = parseRequestLine(reader);
            Map<String, String> headers = parseHeaders(reader);
            Map<String, String> bodyParams = parseBodyParams(reader);

            StringBuilder responseBuilder = new StringBuilder();
            if (isValidRequest(urls, request, headers, bodyParams, responseBuilder)) {
                processRequest(request.get(REQUEST_METHOD), headers, bodyParams, responseBuilder);
            }

            System.out.println(responseBuilder);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.SEVERE, "ERROR", e);
        }
    }

    private static String getResponseLine(HttpResponse response) {
        return String.format(RESPONSE_LINE, response.code, response.name);
    }

    private static String decodeAuthorization(String encoded) {
        if (!encoded.startsWith(AUTHORIZATION_PREFIX)) {
            throw new IllegalArgumentException("Unknown encoding for string: " + encoded);
        }

        return new String(Base64.getDecoder()
                .decode(encoded.substring(AUTHORIZATION_PREFIX.length()).getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    private static void attachResponseHeaders(Map<String, String> headers,
                                              StringBuilder responseBuilder) {
        headers.entrySet().stream().filter(kvp -> RESPONSE_HEADERS.contains(kvp.getKey()))
                .forEach(kvp -> responseBuilder
                        .append(kvp.getKey())
                        .append(HEADER_SEPARATOR)
                        .append(kvp.getValue())
                        .append(HTTP_LINE_SEPARATOR));
        responseBuilder.append(HTTP_LINE_SEPARATOR);
    }

    private static void buildResponse(Map<String, String> headers,
                                      StringBuilder responseBuilder,
                                      String responseLine,
                                      String responseBody) {
        responseBuilder.append(responseLine).append(HTTP_LINE_SEPARATOR);
        attachResponseHeaders(headers, responseBuilder);
        responseBuilder.append(responseBody);
    }

    private static void processRequest(String method,
                                       Map<String, String> headers,
                                       Map<String, String> bodyParams,
                                       StringBuilder responseBuilder) {
        String username = decodeAuthorization(headers.get(HEADER_AUTHORIZATION));

        String responseBody;
        switch (HttpMethod.valueOf(method)) {
            case GET:
                responseBody = String.format(RESPONSE_BODY_GET, username);
                break;
            case POST:
                String itemName = bodyParams.entrySet().stream().findFirst().orElseThrow().getValue();
                String itemParts = bodyParams.entrySet()
                        .stream().skip(1)
                        .map(param -> String.format(POST_RESPONSE_ITEMS_FORMAT, param.getKey(), param.getValue()))
                        .collect(Collectors.joining(POST_RESPONSE_ITEMS_DELIMITER));
                responseBody = String.format(RESPONSE_BODY_POST, username, itemName, itemParts);
                break;
            default:
                throw new IllegalArgumentException("Unknown or unsupported HTTP method: " + method);
        }

        buildResponse(headers, responseBuilder,
                getResponseLine(HttpResponse.OK), responseBody);
    }

    private static boolean isValidRequest(Set<String> urls,
                                          Map<String, String> request,
                                          Map<String, String> headers,
                                          Map<String, String> bodyParams,
                                          StringBuilder responseBuilder) {
        if (!urls.contains(request.get(REQUEST_RESOURCE))) {
            buildResponse(headers, responseBuilder,
                    getResponseLine(HttpResponse.NOT_FOUND),
                    HttpResponse.NOT_FOUND.description);
        } else if (!headers.containsKey(HEADER_AUTHORIZATION)) {
            buildResponse(headers, responseBuilder,
                    getResponseLine(HttpResponse.UNAUTHORIZED),
                    HttpResponse.UNAUTHORIZED.description);
        } else if (HttpMethod.POST == HttpMethod.valueOf(request.get(REQUEST_METHOD)) && bodyParams.isEmpty()) {
            buildResponse(headers, responseBuilder,
                    getResponseLine(HttpResponse.BAD_REQUEST),
                    HttpResponse.BAD_REQUEST.description);
        } else {
            return true;
        }

        return false;
    }

    private static Set<String> parseUrls(BufferedReader reader) throws IOException {
        Set<String> urls = new HashSet<>();

        String urlsLine = reader.readLine();
        Matcher matcher = URLS_PATTERN.matcher(urlsLine);
        while (matcher.find()) {
            urls.add(matcher.group());
        }

        return Collections.unmodifiableSet(urls);
    }

    private static Map<String, String> parseRequestLine(BufferedReader reader) throws IOException {
        String requestLine = reader.readLine();
        Matcher matcher = REQUEST_LINE_PATTERN.matcher(requestLine);

        if (!matcher.matches() ||
                !HTTP_VERSIONS.contains(matcher.group(REQUEST_HTTP_VERSION)) ||
                !HTTP_METHODS.contains(matcher.group(REQUEST_METHOD))) {
            throw new IllegalArgumentException("Invalid Request Line: " + requestLine);
        }

        String httpVersion = matcher.group(REQUEST_HTTP_VERSION);
        String httpMethod = matcher.group(REQUEST_METHOD);
        String resource = matcher.group(REQUEST_RESOURCE);

        return Map.of(REQUEST_METHOD, httpMethod,
                REQUEST_RESOURCE, resource,
                REQUEST_HTTP_VERSION, httpVersion);
    }

    private static Map<String, String> parseHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String header;

        while ((header = reader.readLine()) != null && header.length() > 0) {
            Matcher matcher = HEADER_PATTERN.matcher(header);
            if (matcher.matches() && VALID_HEADERS.contains(matcher.group(KEY))) {
                String key = matcher.group(KEY);
                String value = matcher.group(VALUE);
                headers.put(key, value);
            }
        }

        return Collections.unmodifiableMap(headers);
    }

    private static Map<String, String> parseBodyParams(BufferedReader reader) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();

        String paramsLine = reader.readLine();
        if (paramsLine != null) {
            Matcher matcher = BODY_PARAMS_PATTERN.matcher(paramsLine);
            while (matcher.find()) {
                String paramName = matcher.group(KEY);
                String paramValue = matcher.group(VALUE);
                params.put(paramName, paramValue);
            }
        }

        return Collections.unmodifiableMap(params);
    }

    private enum HttpMethod {GET, POST}

    private enum HttpResponse {
        OK(200, "OK", "Success"),

        BAD_REQUEST(400, "Bab Request",
                "There was an error with the requested functionality due to malformed request."),

        UNAUTHORIZED(401, "Unauthorized",
                "You are not authorized to access the requested functionality."),

        NOT_FOUND(404, "Not Found",
                "The requested functionality was not found.");

        private final int code;
        private final String name;
        private final String description;

        HttpResponse(int code, String name, String description) {
            this.code = code;
            this.name = name;
            this.description = description;
        }
    }
}