package utils.handlers;

import utils.http.*;
import utils.readers.HttpReader;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ResponseHandler implements Handler {

    private static final Pattern URLS_PATTERN = Pattern.compile("/[^ ]+");

    private static final String HEADER_DATE = "Date";
    private static final String HEADER_HOST = "Host";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String AUTHORIZATION_PREFIX = "Basic ";

    private static final Set<String> RESPONSE_HEADERS = Set.of(HEADER_DATE, HEADER_HOST, HEADER_CONTENT_TYPE);

    private static final String RESPONSE_BODY_GET = "Greetings %s!";
    private static final String RESPONSE_BODY_POST = RESPONSE_BODY_GET + " You have successfully created %s with %s.";
    private static final String RESPONSE_BODY_POST_ITEMS_FORMAT = "%s - %s";
    private static final String RESPONSE_BODY_POST_ITEMS_DELIMITER = ", ";
    private static final String RESPONSE_BODY_NOT_FOUND = "The requested functionality was not found.";
    private static final String RESPONSE_BODY_UNAUTHORIZED =
            "You are not authorized to access the requested functionality.";
    private static final String RESPONSE_BODY_BAD_REQUEST =
            "There was an error with the requested functionality due to malformed request.";

    private final Consumer<String> resultConsumer;
    private final HttpReader httpReader;

    public ResponseHandler(HttpReader httpReader, Consumer<String> resultConsumer) {
        this.resultConsumer = resultConsumer;
        this.httpReader = httpReader;
    }

    private static Set<String> parseUrls(String urlsLine) {
        Set<String> urls = new HashSet<>();
        if (urlsLine != null) {
            Matcher matcher = URLS_PATTERN.matcher(urlsLine);
            while (matcher.find()) {
                urls.add(matcher.group());
            }
        }

        return Collections.unmodifiableSet(urls);
    }

    private static String decodeAuthorization(String encoded) {
        if (!encoded.startsWith(AUTHORIZATION_PREFIX)) {
            throw new IllegalArgumentException("Unknown encoding for string: " + encoded);
        }

        return new String(Base64.getDecoder()
                .decode(encoded.substring(AUTHORIZATION_PREFIX.length()).getBytes(HttpConstants.CHARSET)),
                HttpConstants.CHARSET);
    }

    private static utils.http.HttpResponse buildResponse(Set<String> validUrls, utils.http.HttpRequest request) {
        utils.http.HttpResponse response = new HttpResponseImpl();

        request.getHeaders()
                .entrySet()
                .stream()
                .filter(header -> RESPONSE_HEADERS.contains(header.getKey()))
                .forEach(header -> response.addHeader(header.getKey(), header.getValue()));

        if (!validUrls.contains(request.getRequestUrl())) {
            response.setHttpStatus(HttpStatus.NOT_FOUND);
            response.setContent(RESPONSE_BODY_NOT_FOUND.getBytes(HttpConstants.CHARSET));
        } else if (!request.getHeaders().containsKey(HEADER_AUTHORIZATION)) {
            response.setHttpStatus(HttpStatus.UNAUTHORIZED);
            response.setContent(RESPONSE_BODY_UNAUTHORIZED.getBytes(HttpConstants.CHARSET));
        } else if ((request.getMethod() == HttpMethod.POST) && request.getBodyParameters().isEmpty()) {
            response.setHttpStatus(HttpStatus.BAD_REQUEST);
            response.setContent(RESPONSE_BODY_BAD_REQUEST.getBytes(HttpConstants.CHARSET));
        } else {
            response.setHttpStatus(HttpStatus.OK);
            String username = decodeAuthorization(request.getHeaders().get(HEADER_AUTHORIZATION));
            switch (request.getMethod()) {
                case POST:
                    String itemName = request.getBodyParameters().entrySet().stream().findFirst().orElseThrow().getValue();
                    String itemParts = request.getBodyParameters().entrySet()
                            .stream()
                            .skip(1)
                            .map(param -> String.format(RESPONSE_BODY_POST_ITEMS_FORMAT, param.getKey(), param.getValue()))
                            .collect(Collectors.joining(RESPONSE_BODY_POST_ITEMS_DELIMITER));
                    response.setContent(String.format(RESPONSE_BODY_POST, username, itemName, itemParts)
                            .getBytes(HttpConstants.CHARSET));
                    break;
                case GET:
                    response.setContent(String.format(RESPONSE_BODY_GET, username).getBytes(HttpConstants.CHARSET));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown or unsupported HTTP method: " + request.getMethod());
            }
        }

        return response;
    }

    @Override
    public void handle() throws IOException {
        Set<String> validUrls = parseUrls(httpReader.readLine());
        String request = httpReader.readHttpRequest();
        HttpRequest httpRequest = new HttpRequestImpl(request);
        HttpResponse httpResponse = buildResponse(validUrls, httpRequest);
        resultConsumer.accept(new String(httpResponse.getBytes(), HttpConstants.CHARSET));
    }
}
