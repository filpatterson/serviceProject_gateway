package Main;

import com.lambdaworks.redis.RedisAsyncConnection;
import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisConnection;
import com.lambdaworks.redis.RedisURI;

import java.util.concurrent.ExecutionException;

//  simple class for establishing connection to redis
public class GatewayEntity {
    public static void main (String[] args) throws InterruptedException {
        //  establish communication channel with Redis. There can be only one channel for safe implementation of
        // transactions
        RedisClient redisClient = new RedisClient(
                RedisURI.create("redis://@localhost:6379"));

        //  simple connection
        RedisConnection<String, String> connection = redisClient.connect();

        System.out.println(Thread.activeCount());

        for(int i = 0; i < 100; i++) {
            int finalI = i;
            new Thread(() -> {
                //  create communicator for interaction with Redis with Thread-safe principle of connection
                RedisConnection<String, String> localConnection = redisClient.connect();

                //  set element to db
                localConnection.set("foo" + finalI, "bar" + finalI);

                //  get element with requested key from db
                System.out.println(localConnection.get("foo" + finalI));
            }).start();
        }

        Thread.sleep(2000);

        for(int i = 0; i < 100; i++) {
            int finalI = i;
            new Thread(() -> {
                //  create async communicator for interaction with Redis
                RedisAsyncConnection<String, String> localConnection = redisClient.connectAsync();
                //  set element to db
                localConnection.set("async foo" + finalI, "async bar" + finalI);

                //  get element with requested key from db
                try {
                    //  get() in async connection returns Future object containing String
                    System.out.println(localConnection.get("async foo" + finalI).get());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }).start();
        }

        System.out.println(connection.dbsize());

        System.out.println(connection.get("foo"));

        System.out.println("Connected to Redis");

//        connection.close();
//        redisClient.shutdown();
    }
}
