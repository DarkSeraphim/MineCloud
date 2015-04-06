/*
 * Copyright (c) 2015, Mazen Kotb <email@mazenmc.io>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package io.minecloud.daemon;

import com.mongodb.BasicDBObject;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.messages.Container;
import io.minecloud.MineCloud;
import io.minecloud.db.Credentials;
import io.minecloud.db.mongo.MongoDatabase;
import io.minecloud.db.redis.RedisDatabase;
import io.minecloud.db.redis.msg.MessageType;
import io.minecloud.db.redis.msg.binary.MessageInputStream;
import io.minecloud.db.redis.pubsub.SimpleRedisChannel;
import io.minecloud.models.bungee.Bungee;
import io.minecloud.models.bungee.type.BungeeType;
import io.minecloud.models.network.Network;
import io.minecloud.models.nodes.Node;
import io.minecloud.models.nodes.NodeRepository;
import io.minecloud.models.server.Server;
import io.minecloud.models.server.type.ServerType;
import org.apache.logging.log4j.Level;
import org.bson.types.ObjectId;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

public class MineCloudDaemon {
    private static MineCloudDaemon instance;

    private final String node;
    private final DockerClient dockerClient;
    private final RedisDatabase redis;
    private final MongoDatabase mongo;

    private MineCloudDaemon(Properties properties) {
        redis = MineCloud.instance().redis();
        mongo = MineCloud.instance().mongo();
        dockerClient = new DefaultDockerClient("unix:///var/run/docker.sock");

        node = (String) properties.get("node-name");
        instance = this;

        redis.addChannel(SimpleRedisChannel.create("server-create", redis)
                .addCallback((message) -> {
                    if (message.type() != MessageType.BINARY) {
                        return;
                    }

                    MessageInputStream stream = message.contents();

                    if (!stream.readString().equalsIgnoreCase(node))
                        return;

                    Network network = mongo.repositoryBy(Network.class)
                            .findFirst(new BasicDBObject("name", stream.readString()));
                    ServerType type = mongo.repositoryBy(ServerType.class)
                            .findFirst(new BasicDBObject("name", stream.readString()));

                    Deployer.deployServer(network, type);
                }));

        redis.addChannel(SimpleRedisChannel.create("server-kill", redis)
                .addCallback((message) -> {
                    if (message.type() != MessageType.BINARY) {
                        return;
                    }

                    MessageInputStream stream = message.contents();

                    if (!stream.readString().equalsIgnoreCase(node))
                        return;

                    Server server = mongo.repositoryBy(Server.class)
                            .findFirst(new ObjectId(stream.readString()));

                    if (!server.node().name().equals(node)) {
                        MineCloud.logger().log(Level.ERROR, "Invalid request was sent to kill a server " +
                                "not on the current node");
                        return;
                    }

                    try {
                        dockerClient.killContainer(server.containerId());
                        MineCloud.logger().info("Killed server " + server.name()
                                + " with container id " + server.containerId());

                        mongo.repositoryBy(Server.class).remove(server);
                    } catch (DockerException | InterruptedException e) {
                        MineCloud.logger().log(Level.ERROR, "Was unable to kill a server", e);
                    }
                }));

        redis.addChannel(SimpleRedisChannel.create("bungee-create", redis)
                .addCallback((message) -> {
                    if (message.type() != MessageType.BINARY) {
                        return;
                    }

                    MessageInputStream stream = message.contents();

                    if (!stream.readString().equalsIgnoreCase(node))
                        return;

                    Network network = mongo.repositoryBy(Network.class)
                            .findFirst(new BasicDBObject("name", stream.readString()));
                    BungeeType type = mongo.repositoryBy(BungeeType.class)
                            .findFirst(new BasicDBObject("name", stream.readString()));

                    Deployer.deployBungee(network, type);
                }));

        redis.addChannel(SimpleRedisChannel.create("bungee-kill", redis)
                .addCallback((message) -> {
                    if (message.type() != MessageType.BINARY) {
                        return;
                    }

                    MessageInputStream stream = message.contents();

                    if (!stream.readString().equalsIgnoreCase(node))
                        return;

                    Bungee bungee = mongo.repositoryBy(Bungee.class)
                            .findFirst(new ObjectId(stream.readString()));

                    if (!bungee.node().name().equals(node)) {
                        MineCloud.logger().log(Level.ERROR, "Invalid request was sent to kill a bungee " +
                                "not on the current node");
                        return;
                    }

                    try {
                        dockerClient.killContainer(bungee.containerId());
                        MineCloud.logger().info("Killed bungee " + bungee.name()
                                + " with container id " + bungee.containerId());

                        mongo.repositoryBy(Bungee.class).remove(bungee);
                    } catch (DockerException | InterruptedException e) {
                        MineCloud.logger().log(Level.ERROR, "Was unable to kill a server", e);
                    }
                }));

        new StatisticsWatcher().start();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                dockerClient.listContainers().stream()
                        .filter((container) -> container.status().contains("exited"))
                        .forEach((container) -> {
                            try {
                                dockerClient.killContainer(container.id());

                                if (container.image().contains("minecloud")) {
                                    String type = container.image().substring(9);

                                    switch (type.toLowerCase()) {
                                        case "bungee":
                                            mongo.repositoryBy(Bungee.class)
                                                    .remove((bungee) -> bungee
                                                            .containerId().equals(container.id()));
                                            break;

                                        case "server":
                                            mongo.repositoryBy(Server.class)
                                                    .remove((server) -> server
                                                            .containerId().equals(container.id()));
                                            break;
                                    }
                                }

                               MineCloud.logger().info("Killed dead container " + container.id());
                            } catch (DockerException | InterruptedException e) {
                                MineCloud.logger().log(Level.ERROR, "Was unable to kill exited container " + container.id(),
                                        e);
                            }
                        });
            } catch (DockerException | InterruptedException ignored) {}

            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ignored) {
                // I don't care
            }
        }
    }

    public Node node() {
        return ((NodeRepository) mongo.repositoryBy(Node.class)).nodeBy(node);
    }

    public DockerClient dockerClient() {
        return dockerClient;
    }

    public static MineCloudDaemon instance() {
        return instance;
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        File configFolder = new File("/etc/minecloud/");
        File file = new File(configFolder, "daemon/details.properties");

        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }

        if (!file.exists()) {
            file.createNewFile();
        }

        properties.load(new FileInputStream(file));

        if (!properties.containsKey("mongo-hosts")) {
            MineCloud.runSetup(properties, file);
            new MineCloudDaemon(properties);

            properties = null;
            return;
        }

        Credentials mongo = new Credentials(properties.getProperty("mongo-hosts").split(";"),
                properties.getProperty("mongo-username"),
                properties.getProperty("mongo-password").toCharArray(),
                properties.getProperty("mongo-database"));
        Credentials redis = new Credentials(new String[] {properties.getProperty("redis-host")},
                properties.getProperty("redis-username"),
                properties.getProperty("redis-password").toCharArray());

        MineCloud.instance().initiateMongo(mongo);
        MineCloud.instance().initiateRedis(redis);

        new MineCloudDaemon(properties);
    }
}