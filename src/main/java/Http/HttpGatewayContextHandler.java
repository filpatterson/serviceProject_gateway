package Http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lambdaworks.redis.RedisConnection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HttpGatewayContextHandler implements HttpHandler {
    RedisConnection<String, String> redisConnection = null;
    HttpUtility httpUtility = null;

    //  constructor to establish connection with db and with redis
    public HttpGatewayContextHandler(RedisConnection<String, String> redisConnection, HttpUtility httpUtility) {
        this.redisConnection = redisConnection;
        this.httpUtility = httpUtility;
    }

    /**
     * handle for all incoming requests
     * @param httpExchange REST service connector
     */
    @Override
    public void handle(HttpExchange httpExchange) {
        try {
            //  check if this is GET request
            if("GET".equals(httpExchange.getRequestMethod())) {
                handleGetResponse(httpExchange);
                return;
            }

            //  otherwise, request must have payload in its body
            String requestBody;
            //  try to get payload from request body
            if ((requestBody = getRequestPayload(httpExchange)) == null) {
                System.err.println("Couldn't take content of request");
                return;
            }

            //  handle request basing on type of request
            if("POST".equals(httpExchange.getRequestMethod())) {
                handlePostResponse(httpExchange, requestBody);
            } else if("PUT".equals(httpExchange.getRequestMethod())) {
                handlePutResponse(httpExchange, requestBody);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * get payload from incoming request
     * @param httpExchange REST service connector
     * @return string object containing payload of incoming request
     * @throws IOException error in process of reading request
     */
    private String getRequestPayload(HttpExchange httpExchange) throws IOException {
        //  check that there is specified content type of request
        if(httpExchange.getRequestHeaders().containsKey("Content-Type")) {
            //  check content to be equal to json formatted data
            if(httpExchange.getRequestHeaders().get("Content-Type").get(0).equals("application/json")){
                //  open stream for getting UTF-8 formatted characters
                InputStreamReader inputStreamReader = new InputStreamReader(httpExchange.getRequestBody(), StandardCharsets.UTF_8);

                //  insert stream to buffer for reading through input stream
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                //  initialize current character and buffer for appending all incoming characters
                int currentCharacter;
                StringBuilder buffer = new StringBuilder(512);

                //  while it's not the end of all stream, read char-by-char all incoming data
                while((currentCharacter = bufferedReader.read()) != -1) {
                    buffer.append((char) currentCharacter);
                }

                //  close buffer and input stream
                bufferedReader.close();
                inputStreamReader.close();

                //  return string-formatted data
                return buffer.toString();
            } else {
                System.err.println("Unknown content-type");
                JSONObject jsonResponse = new JSONObject();
                jsonResponse.put("error", "unknown content-type");
                sendResponse(httpExchange, jsonResponse.toString());
            }
        } else {
            System.err.println("No content-type specified");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "content-type not specified");
            sendResponse(httpExchange, jsonResponse.toString());
        }
        return null;
    }

    /**
     * generate response to POST request and send it back to client
     * @param httpExchange REST service connector
     * @param requestPayload payload of received request
     * @throws IOException i/o exception
     */
    private void handlePostResponse(HttpExchange httpExchange, String requestPayload)  throws  IOException {
        //  start deserialization of json payload
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode node = objectMapper.readValue(requestPayload, ObjectNode.class);

        //  get name of requested service and make error response if none
        String nameOfService = node.get("functionName").asText();
        if(nameOfService == null) {
            System.err.println("invalid request");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "function name not found in request");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  check if received request is one for establishing connection to service
        if(httpExchange.getRequestHeaders().containsKey("Service-Call")) {
            if(httpExchange.getRequestHeaders().get("Service-Call").get(0).equals("true")) {

                //  get address of service
                String addressOfService = node.get("address").asText();

                //  append route to service in array and initialize queue size for this service
                if(!redisConnection.exists(addressOfService + "_mailboxSize")) {
                    redisConnection.lpush(nameOfService, addressOfService);
                    redisConnection.set(addressOfService + "_mailboxSize", "0");
                }

                //  make response of successful connection establishment for service
                JSONObject jsonResponse = new JSONObject();
                jsonResponse.put("status", "successful connection to gateway");
                jsonResponse.put("address", addressOfService);
                sendResponse(httpExchange, jsonResponse.toString());
                return;
            }
        }

        //  find how many services are there with such command and send error if none
        long amountOfServices = redisConnection.llen(nameOfService);
        if(amountOfServices == 0) {
            System.err.println("no services found");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "not found service with such function name");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  check list of all services with such functionality
        List<String> availableServicesRoutes = redisConnection.lrange(nameOfService, 0, amountOfServices - 1);

        //  find least occupied service at moment
        Integer leastMailboxSize = null;
        String leastOccupiedService = null;
        for(int i = 0; i < availableServicesRoutes.size(); i++) {
            //  get service mailbox size
            int mailboxSize = Integer.parseInt(redisConnection.get(availableServicesRoutes.get(i) + "_mailboxSize"));
            //  initialize local variables if they're not yet
            if(leastMailboxSize == null || leastOccupiedService == null){
                leastMailboxSize = mailboxSize;
                leastOccupiedService = availableServicesRoutes.get(i);
            }
            //  if mailbox of service is less than current one, then save it
            if(leastMailboxSize > mailboxSize)
                leastOccupiedService = availableServicesRoutes.get(i);
        }

        //  redirect request to service
        String serviceResponse = httpUtility.sendJsonPost(leastOccupiedService, requestPayload);

        //  if there is no response then send error
        if(serviceResponse == null) {
            System.err.println("there is no response received");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "no response to POST from service");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  update service mailbox size increasing it by one
        redisConnection.set(leastOccupiedService + "_mailboxSize", String.valueOf(++leastMailboxSize));

        //  deserialize response, get id and give error if there is no ID
        node = objectMapper.readValue(serviceResponse, ObjectNode.class);
        String id = node.get("id").asText();
        if(id == null) {
            System.err.println("response has no ID");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "service response to POST has no ID");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  register process in redis
        redisConnection.set(id, leastOccupiedService);

        //  redirect response to client
        sendResponse(httpExchange, serviceResponse);
    }

    /**
     * generate response to PUT request and send it back to client
     * @param httpExchange REST service connector
     * @param requestPayload payload of received request
     * @throws IOException i/o exception
     */
    private void handlePutResponse(HttpExchange httpExchange, String requestPayload) throws IOException {
        //  start deserialization of json payload
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode node = objectMapper.readValue(requestPayload, ObjectNode.class);

        //  check if there is ID and give error if none
        String packetIndex = node.get("id").asText();
        if(packetIndex == null) {
            System.err.println("id in PUT request is not found");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "not found ID in PUT request");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  find how many services are there with such command, send error if none
        String routeToService = redisConnection.get(packetIndex);
        if(routeToService == null) {
            System.err.println("there is no process on any service with such ID");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "no process with such ID");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  redirect request, get response, send error if there none
        String serviceResponse = httpUtility.sendJsonPut(routeToService, requestPayload);
        if(serviceResponse == null) {
            System.err.println("no answer on PUT request");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "no response to PUT request");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  redirect response to client
        sendResponse(httpExchange, serviceResponse);
    }

    /**
     * generate response to GET request and send it back to client
     * @param httpExchange REST service connector
     * @throws Exception i/o exception
     */
    private void handleGetResponse(HttpExchange httpExchange) throws Exception {
        //  get from uri required process ID
        long requestedIndex = Long.parseLong(httpExchange.getRequestURI().toString().
                split("\\?")[1].
                split("=")[1]);

        //  find how many services are there with such command
        String routeToService = redisConnection.get(String.valueOf(requestedIndex));
        System.out.println(requestedIndex);
        if(routeToService == null) {
            System.err.println("there is no process on any service with such ID");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "no process with such ID");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  send get request and if there is no response - send error
        String serviceResponse = httpUtility.sendJsonGet(routeToService+ "?id=" + requestedIndex);
        if(serviceResponse == null) {
            System.err.println("there is no response received to GET");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "no response from service to GET");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  decrement size of service mailbox
        int mailboxSize = Integer.parseInt(redisConnection.get(routeToService + "_mailboxSize"));
        redisConnection.set(routeToService + "_mailboxSize", String.valueOf(--mailboxSize));

        //  deserialize response
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode node = objectMapper.readValue(serviceResponse, ObjectNode.class);

        //  get id and send error if there is none
        String responseId = node.get("id").asText();
        if(responseId == null) {
            System.err.println("response has no ID");
            JSONObject jsonResponse = new JSONObject();
            jsonResponse.put("error", "response from service to GET has no ID");
            sendResponse(httpExchange, jsonResponse.toString());
            return;
        }

        //  remove process from redis and redirect response to client
        redisConnection.del(responseId);
        sendResponse(httpExchange, serviceResponse);
    }

    /**
     * send response to client
     * @param httpExchange REST service connector
     * @param response generated response for client
     * @throws IOException i/o exception
     */
    private void sendResponse(HttpExchange httpExchange, String response) throws IOException {
        //  set headers of response
        httpExchange.getResponseHeaders().set("Content-Type", "application/json");
        httpExchange.sendResponseHeaders(200, response.length());
        System.out.println(response);

        //  send response to the client
        OutputStream outputStream = httpExchange.getResponseBody();
        outputStream.write(response.getBytes());
        outputStream.flush();
        outputStream.close();
    }
}