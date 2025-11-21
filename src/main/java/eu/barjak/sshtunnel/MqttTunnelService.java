package eu.barjak.sshtunnel;

import org.eclipse.paho.client.mqttv3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MqttTunnelService {
    @Value("${mqtt.broker:tcp://localhost:1883}")
    private String mqttBroker;

    @Value("${mqtt.topic:zigbee2mqtt/#}")
    private String mqttTopic;

    @Value("${ssh.cmd}")
    private String sshCmd;

    @Value("${pkill.cmd}")
    private String pkillCmd;

    @Value("${timeout.seconds:60}")
    private long timeoutSeconds;

    private final AtomicLong lastMessageTime = new AtomicLong(System.currentTimeMillis());
    private MqttClient client;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void start() {
        try {
            client = new MqttClient(mqttBroker, "Sshtunnel-Spring-" + UUID.randomUUID());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.out.println("Connection lost: " + cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    lastMessageTime.set(System.currentTimeMillis());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            tryConnectAndSubscribe(opts);

            scheduler.scheduleAtFixedRate(this::monitor, 5, 5, TimeUnit.SECONDS);
        } catch (MqttException e) {
            System.err.println("Failed to start MQTT client: " + e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
            } catch (MqttException ignored) {
            }
        }
    }

    private void monitor() {
        long diff = (System.currentTimeMillis() - lastMessageTime.get()) / 1000;
        if (diff > timeoutSeconds) {
            System.out.println("No message received for " + diff + "s, restarting tunnel and reconnecting...");
            restartTunnelAndResubscribe();
            lastMessageTime.set(System.currentTimeMillis());
        }
    }

    private void tryConnectAndSubscribe(MqttConnectOptions opts) {
        try {
            client.connect(opts);
            client.subscribe(mqttTopic);
            System.out.println("Connected and subscribed to " + mqttTopic);
        } catch (MqttException e) {
            System.out.println("Connection error: " + e.getMessage());
            System.out.println("Starting SSH tunnel and retrying...");
            try {
                runCommand(pkillCmd);
                runCommand(sshCmd);
                Thread.sleep(2000);
                client.connect(opts);
                client.subscribe(mqttTopic);
                System.out.println("SSH tunnel started and reconnected.");
            } catch (Exception ex) {
                System.err.println("Failed to start tunnel or reconnect: " + ex.getMessage());
            }
        }
    }

    private void restartTunnelAndResubscribe() {
        try {
            runCommand(pkillCmd);
            runCommand(sshCmd);
            if (client.isConnected()) {
                try {
                    client.disconnect();
                } catch (Exception ignore) {}
            }
            Thread.sleep(1000);
            client.connect();
            client.subscribe(mqttTopic);
            System.out.println("SSH tunnel restarted, reconnected and resubscribed.");
        } catch (Exception e) {
            System.err.println("Error restarting tunnel: " + e.getMessage());
        }
    }

    private void runCommand(String cmd) throws IOException, InterruptedException {
        if (cmd == null || cmd.trim().isEmpty()) return;
        System.out.println("Running: " + cmd);
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
            }
        }
        p.waitFor();
    }
}
