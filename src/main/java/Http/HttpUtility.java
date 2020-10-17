package Http;

import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Class for making http requests
 */
public class HttpUtility {
    //  setting HttpClient that will make requests
    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * send simple GET request without header specifications and additional info
     * @param destinationPage page for requesting data from
     * @return data obtained as result of GET request to the page
     * @throws IOException error in I/O streams of application
     * @throws InterruptedException error in threading
     */
    public String sendGet(String destinationPage) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(destinationPage)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }

    public void sendJsonPost(String destinationPage, String functionName, String firstArgument) throws IOException {
        String payload = "{\"functionName\":\"" + functionName +"\",\"amount\":"+ firstArgument +"}";
        StringEntity entity = new StringEntity(payload,
                ContentType.APPLICATION_JSON);

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost request = new HttpPost(destinationPage);
        request.setHeader("Accept", "application/json");
        request.setHeader("Content-Type", "application/json");
        request.setEntity(entity);

        CloseableHttpResponse response = httpClient.execute(request);
        ResponseHandler<String> handler = new BasicResponseHandler();
        System.out.println(handler.handleResponse(response));
    }

    public void sendJsonPut(String destinationPage, Long id, String nameOfArgument, String argumentValue) throws IOException {
        String payload = "{\"id\":" + id + ",\"" + nameOfArgument + "\":\"" + argumentValue + "\"}";
        StringEntity entity = new StringEntity(payload,
                ContentType.APPLICATION_JSON);

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPut request = new HttpPut(destinationPage);
        request.setHeader("Accept", "application/json");
        request.setHeader("Content-Type", "application/json");
        request.setEntity(entity);

        CloseableHttpResponse response = httpClient.execute(request);
        ResponseHandler<String> handler = new BasicResponseHandler();
        System.out.println(handler.handleResponse(response));
    }

    public void sendJsonGet(String destinationPageWithId) throws IOException {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet request = new HttpGet(destinationPageWithId);

        CloseableHttpResponse response = httpClient.execute(request);
        ResponseHandler<String> handler = new BasicResponseHandler();
        System.out.println(handler.handleResponse(response));
    }
}
