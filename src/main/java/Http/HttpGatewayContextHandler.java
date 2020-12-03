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
    //  redis connection entity for performing all actions
    // note: to make this thing work, launch at docker Redis image
    RedisConnection<String, String> redisConnection;

    //  instance of utility for HTTP operations;
    HttpUtility httpUtility;

    //  constructor to establish connection with db and with redis
    public HttpGatewayContextHandler(RedisConnection<String, String> redisConnection, HttpUtility httpUtility) {
        this.redisConnection = redisConnection;
        this.httpUtility = httpUtility;
    }

    /**
     * handle for all incoming to the gateway requests
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
            if ("POST".equals(httpExchange.getRequestMethod())) {
                handlePostResponse(httpExchange, requestBody);
            } else if ("PUT".equals(httpExchange.getRequestMethod())) {
                handlePutResponse(httpExchange, requestBody);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * get payload from incoming request
     * @param httpExchange REST service connector
     * @return string formatted payload of request
     * @throws IOException error in process of reading request
     */
    private String getRequestPayload(HttpExchange httpExchange) throws IOException {
        //  check that there is specified content type of request
        if(httpExchange.getRequestHeaders().containsKey("Content-Type")) {
            //  check content to be equal to json formatted data
            if(httpExchange.getRequestHeaders().get("Content-Type").get(0).equals("application/json")){
                //  open stream for getting UTF-8 formatted characters
                InputStreamReader inputStreamReader = new InputStreamReader(
                        httpExchange.getRequestBody(), StandardCharsets.UTF_8
                );

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
                sendErrorResponse(httpExchange, "Content-Type error: content-type is unknown");
            }
        } else {
            sendErrorResponse(httpExchange, "Content-Type error: no content-type specification");
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
            sendErrorResponse(httpExchange, "invalid POST request: function name not found in request");
            return;
        }

        //  check if received request is one for establishing connection between gateway and service
        if(httpExchange.getRequestHeaders().containsKey("Service-Call")) {
            if(httpExchange.getRequestHeaders().get("Service-Call").get(0).equals("true")) {

                //  get address of service
                String addressOfService = node.get("address").asText();

                //  push service address to the Redis and create counter for service current load state
                if(!redisConnection.exists(addressOfService + "_mailboxSize")) {
                    redisConnection.lpush(nameOfService, addressOfService);
                    redisConnection.set(addressOfService + "_mailboxSize", "0");
                } else {
                //  if there already is such service then send error message
                    sendErrorResponse(httpExchange, "REDIS error: service with such address exists");
                    return;
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
            sendErrorResponse(
                    httpExchange, "invalid POST request: not found service with such function name"
            );
            return;
        }

        //  get all elements that are registered in Redis for this command
        List<String> availableServicesRoutes = redisConnection.lrange(nameOfService, 0, amountOfServices - 1);

        //  initialize variables for finding least occupied service
        Integer leastMailboxSize = null;
        String leastOccupiedService = null;

        //  find least occupied service at moment
        for(int i = 0; i < availableServicesRoutes.size(); i++) {
            //  get service mailbox size
            int mailboxSize = Integer.parseInt(redisConnection.get(availableServicesRoutes.get(i) + "_mailboxSize"));

            //  initialize local variables if they're not yet
            if(leastMailboxSize == null || leastOccupiedService == null){
                leastMailboxSize = mailboxSize;
                leastOccupiedService = availableServicesRoutes.get(i);
            }

            //  if mailbox of service is less than current one, then save it
            if(leastMailboxSize > mailboxSize) {
                leastOccupiedService = availableServicesRoutes.get(i);
            }
        }

        //  redirect request to service
        String serviceResponse = httpUtility.sendJsonPost(leastOccupiedService, requestPayload);

        //  if there is no response then send error
        if(serviceResponse == null) {
            sendErrorResponse(
                    httpExchange, "invalid POST service response: no response to POST from service"
            );
            return;
        }

        //  update service mailbox size increasing it by one
        redisConnection.set(leastOccupiedService + "_mailboxSize", String.valueOf(++leastMailboxSize));

        //  deserialize response, get ID and give error if there is no ID
        node = objectMapper.readValue(serviceResponse, ObjectNode.class);
        String id = node.get("id").asText();
        if(id == null) {
            sendErrorResponse(httpExchange, "invalid POST service response: response has no ID");
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
            sendErrorResponse(httpExchange, "invalid PUT request: not specified ID of process");
            return;
        }

        //  find how many services are there with such command, send error if none
        String routeToService = redisConnection.get(packetIndex);
        if(routeToService == null) {
            sendErrorResponse(httpExchange, "invalid PUT request: no service has process with this ID");
            return;
        }

        //  redirect request, get response, send error if there none
        String serviceResponse = httpUtility.sendJsonPut(routeToService, requestPayload);
        if(serviceResponse == null) {
            sendErrorResponse(
                    httpExchange, "invalid PUT service response: no response to PUT from service"
            );
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

        if(routeToService == null) {
            sendErrorResponse(httpExchange, "invalid GET request: no service has process with this ID");
            return;
        }

        //  send get request and if there is no response - send error
        String serviceResponse = httpUtility.sendJsonGet(routeToService+ "?id=" + requestedIndex);
        if(serviceResponse == null) {
            sendErrorResponse(httpExchange, "invalid GET response: there is no response to GET request");
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
            sendErrorResponse(httpExchange, "invalid GET response: response does not have ID");
            return;
        }

        //  remove process from redis and redirect response to client
        redisConnection.del(responseId);
        sendResponse(httpExchange, serviceResponse);
    }

    /**
     * send error response to the client
     * @param httpExchange connection entity
     * @param errorMessage simple string-formatted error message
     */
    private void sendErrorResponse(HttpExchange httpExchange, String errorMessage) throws IOException {
        //  make server print message as if it is error
        System.err.println(errorMessage);

        //  form json object from message and send it to the client
        JSONObject jsonResponse = new JSONObject();
        jsonResponse.put("error", errorMessage);
        sendResponse(httpExchange, jsonResponse.toString());
    }

    /**
     * send response to client
     * @param httpExchange REST service connector
     * @param response generated response for client in JSON-string format
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