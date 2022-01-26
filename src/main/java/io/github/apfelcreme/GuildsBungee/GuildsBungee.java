package io.github.apfelcreme.GuildsBungee;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Guilds
 * Copyright (C) 2015 Lord36 aka Apfelcreme
 * <p>
 * This program is free software;
 * you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, see <http://www.gnu.org/licenses/>.
 *
 * @author Lord36 aka Apfelcreme on 09.06.2015.
 */
public class GuildsBungee extends Plugin implements Listener {

    Map<String, QueueingPluginMessage> pmQueue = new HashMap<>();

    @Override
    public void onEnable() {
        getProxy().registerChannel("guilds:sync");
        getProxy().registerChannel("guilds:player");
        getProxy().registerChannel("guilds:broadcast");
        getProxy().getPluginManager().registerListener(this, this);
    }

    @EventHandler
    public void onPluginMessageReceived(PluginMessageEvent e) throws IOException {
        if (!e.getTag().startsWith("guilds:")) {
            return;
        }
        if (!(e.getSender() instanceof Server)) {
            e.setCancelled(true);
            return;
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(e.getData());
        DataInputStream in = new DataInputStream(stream);
        String operation = e.getTag().split(":")[1];
        String[] args = in.readUTF().split(" ");
        String guild;
        UUID uuid;
        String message;

        switch (operation) {
            case "broadcast":
                String[] uuids = in.readUTF().split(Pattern.quote(","));
                message = in.readUTF();
                for (String uuidString : uuids) {
                    uuid = UUID.fromString(uuidString);
                    sendSingleMessage(uuid, message);
                }
                log(message);
                break;
            case "player":
                uuid = UUID.fromString(in.readUTF());
                if (args[0].equals("message")) {
                    message = in.readUTF();
                    sendSingleMessage(uuid, message);
                } else if (args[0].equals("guildhome")) {
                    guild = in.readUTF();
                    String homeServer = in.readUTF();
                    sendPlayerToGuildHome(uuid, guild, homeServer, (Server) e.getSender());
                }
                break;
            case "sync":
                switch (args[0]) {
                    case "guilds":
                        sendQueueingMessage("sync", "guilds",
                                getProxy().getPlayers().stream().map(p -> p.getUniqueId().toString()).collect(Collectors.joining(" ")));
                        break;
                    case "guild":
                        sendQueueingMessage("sync", "guild",
                                in.readInt(),
                                getProxy().getPlayers().stream().map(p -> p.getUniqueId().toString()).collect(Collectors.joining(" ")));
                        break;
                    case "contracts":
                        sendQueueingMessage("sync", "contracts",
                                getProxy().getPlayers().stream().map(p -> p.getUniqueId().toString()).collect(Collectors.joining(" ")));
                        break;
                    case "contract":
                        sendQueueingMessage("sync", "contract",
                                in.readInt(),
                                getProxy().getPlayers().stream().map(p -> p.getUniqueId().toString()).collect(Collectors.joining(" ")));
                        break;
                    case "alliances":
                        sendQueueingMessage("sync", "alliances");
                        break;
                    case "alliance":
                        sendQueueingMessage("sync", "alliance", in.readInt());
                        break;
                }
                break;
        }
    }

    private void sendQueueingMessage(String operation, String args, Object... write) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(args);
        for (Object w : write) {
            if (w instanceof String) {
                out.writeUTF((String) w);
            } else if (w instanceof Integer) {
                out.writeInt((Integer) w);
            } else {
                try (ByteArrayOutputStream bos = new ByteArrayOutputStream ();
                     ObjectOutputStream oOut = new ObjectOutputStream(bos)){
                    oOut.writeObject(w);
                    out.write(bos.toByteArray());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        QueueingPluginMessage pm = new QueueingPluginMessage(operation, args, out);
        for (ServerInfo serverInfo : getProxy().getServers().values()) {
            if (serverInfo.getPlayers().size() > 0) {
                pm.send(serverInfo);
            }
        }
        if (pm.getSendTo().size() < getProxy().getServers().size()) {
            // plugin message wasn't sent to all servers because no players where online, queue it
            pmQueue.put(pm.getCommand(), pm);
        } else {
            // remove previously queued plugin message if we already send a newer one
            pmQueue.remove(pm.getCommand());
        }
    }

    /**
     * Send queued plugin messages on server switch.
     * The logic inside QueuingPluginMessage.send(ServerInfo) will prevent
     * that it will be send to a server to which it was already send to!
     *
     * @param event the event
     */
    @EventHandler
    public void onPlayerServerSwitch(ServerSwitchEvent event) {
        Iterator<QueueingPluginMessage> it = pmQueue.values().iterator();
        while (it.hasNext()) {
            QueueingPluginMessage pm = it.next();
            if(pm != null) {
                pm.send(event.getPlayer().getServer().getInfo());

                // This is a really lazy check but should be enough to determinate
                // whether or not we sent the plugin message to all the servers
                if (pm.getSendTo().size() >= getProxy().getServers().size()) {
                    // remove it from the queue
                    it.remove();
                }
            }
        }
    }

    /**
     * notifies all servers that a player has joined
     *
     * @param event the event
     */
    @EventHandler
    public void onPlayerLogin(PostLoginEvent event) {
        sendPlayerStatusChange(event.getPlayer().getUniqueId(), true);
    }

    /**
     * notifies all servers that a player has disconnected
     *
     * @param event the event
     */
    @EventHandler
    public void onPlayerLogout(PlayerDisconnectEvent event) {
        sendPlayerStatusChange(event.getPlayer().getUniqueId(), false);
    }

    /**
     * notifies servers about a login or a logout
     *
     * @param uuid     the player
     * @param isOnline the status
     */
    public void sendPlayerStatusChange(UUID uuid, boolean isOnline) {
        for (ServerInfo serverInfo : ProxyServer.getInstance().getServers().values()) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(isOnline ? "joined" : "disconnected");
            out.writeUTF(uuid.toString());
            serverInfo.sendData("guilds:player", out.toByteArray());
        }
    }

    /**
     * log sth
     *
     * @param message the thing to log
     */
    private void log(String message) {
        try {
            if (!new File(getDataFolder() + "/logs/").exists()) {
                new File(getDataFolder() + "/logs/").mkdirs();
            }

            message = message.replaceAll("§[0-f]", "");
            PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(new File(getDataFolder() + "/logs/"
                    + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".log"), true)));
            out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + message);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * sends a message to a player
     *
     * @param uuid    the players uuid
     * @param message the message
     */
    private void sendSingleMessage(UUID uuid, String message) {
        if (getProxy().getPlayer(uuid) != null) {
            getProxy().getPlayer(uuid).sendMessage(TextComponent.fromLegacyText(message));
        }
    }

    /**
     * sends a player to its home
     *
     * @param uuid       the player uuid
     * @param guild      the guild
     * @param homeServer the ip of the server the home is on
     * @param source     the server the message is coming from
     */
    private void sendPlayerToGuildHome(UUID uuid, String guild, String homeServer, Server source)
            throws IOException {
        ServerInfo target = source.getInfo();
        if (!source.getInfo().getAddress().toString().split("/")[1].equals(homeServer)) {
            ProxiedPlayer player = getProxy().getPlayer(uuid);
            if (player != null) {
                if (new InetSocketAddress(homeServer.split(":")[0],
                        Integer.parseInt(homeServer.split(":")[1])).getAddress().isReachable(2000)) {
                    target = getTargetServer(homeServer);
                    if (target != null) {
                        player.connect(target);
                    } else {
                        getLogger().severe("Targetserver nicht gefunden!");
                    }
                } else {
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF("homeServerUnreachable");
                    out.writeUTF(uuid.toString());
                    source.sendData("guilds:error", out.toByteArray());
                    return;
                }
            }
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("home");
        out.writeUTF(uuid.toString());
        out.writeUTF(guild);
        if (target != null) {
            target.sendData("guilds:player", out.toByteArray());
        }
    }

    /**
     * returns the server info with the given ip (xxx.xxx.xxx.xxx:PORT)
     *
     * @param homeServerIP the ip:port
     * @return the serverInfo
     */
    private ServerInfo getTargetServer(String homeServerIP) {
        for (ServerInfo serverInfo : getProxy().getServers().values()) {
            if (serverInfo.getAddress().equals(new InetSocketAddress(homeServerIP.split(":")[0], Integer.parseInt(homeServerIP.split(":")[1])))) {
                return serverInfo;
            }
        }
        return null;
    }

    private class QueueingPluginMessage {
        private final String operation;
        private final String args;
        private final ByteArrayDataOutput out;

        private Set<String> sendTo = new HashSet<>();

        public QueueingPluginMessage(String operation, String args, ByteArrayDataOutput out) {
            this.operation = operation;
            this.args = args;
            this.out = out;
        }

        /**
         * Send this plugin message to a specific server, but only if it wasn't already send to it
         *
         * @param serverInfo The info of the server to send it to
         * @return true if it was sent, false if it was already sent to that server
         */
        public boolean send(ServerInfo serverInfo) {
            if (!sendTo.contains(serverInfo.getName())) {
                sendTo.add(serverInfo.getName());
                serverInfo.sendData("guilds:" + operation, out.toByteArray());
                return true;
            }
            return false;
        }

        /**
         * @return The Operation this plugin message performs
         */
        public String getCommand() {
            return operation + ":" + args;
        }

        /**
         * @return The Set of names of servers this plugin message was send to
         */
        public Set<String> getSendTo() {
            return sendTo;
        }
    }
}
