# Service project: gateway

This part of project contains gateway implementation with connection to Redis service for making
fast cache calls requiring useful data. Each service is registered in gateway for redirecting
requests. Gateway also balances load on service via directing POST-requests for starting operation
on those services that have less requests operated at moment of receiving new one.

Project can be launched via next segment of code inside main class:

```java
    //  establish communication channel with Redis
    RedisClient redisClient = new RedisClient(
            RedisURI.create("redis://@localhost:6379"));

    //  create http server that will handle all incoming requests and responses
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8003), 0);
    ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
    server.createContext("/", new HttpGatewayContextHandler(redisClient.connect(), new HttpUtility()));
    server.setExecutor(threadPoolExecutor);
    server.start();
    System.out.println(" Server started on port 8003");
```

Gateway works as HTTP REST service, redirecting requests basing on load-balancer choices and
to which services were appended processes.

### Service registration

Gateway does not know from the start all services (or their instances) and their properties that
must be used. The only way to implement is via registering services.

This can be done via receiving special POST-requests containing special headers. Those headers show
to gateway that current request was delivered by service and handle payload considering that there
must be information for establishing communication with service:

```java
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
```

### Process registration

Each time client sends POST requests to gateway it redirects them to services waiting for their responses.
If there is one with required data it means that process was successfully created at service-side.
Considering that all next PUT and GET requests are based on directing them to service that has taken
process, gateway must register those ID's to services using Redis.

```java
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
```

At stage of getting result from service gateway checks if answer was received. If it was, then
process is considered as finished and is removed from storage of all active processes:

```java
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
```

### Load balancer
Another problem is that at stage of registering new process and appending it to service, gateway
must choose such one that is less occupied. This is done via next segment of code:

```java
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
```

How do Redis know size of queue of all handled at moment processes? All successful POST requests
increment amount of handled processes and successful GET requests decrement this amount.

```java
//  update service mailbox size increasing it by one
redisConnection.set(leastOccupiedService + "_mailboxSize", String.valueOf(++leastMailboxSize));

//  decrement size of service mailbox
int mailboxSize = Integer.parseInt(redisConnection.get(routeToService + "_mailboxSize"));
redisConnection.set(routeToService + "_mailboxSize", String.valueOf(--mailboxSize));
```