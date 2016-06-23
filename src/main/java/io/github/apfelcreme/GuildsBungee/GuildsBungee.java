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
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Pattern;

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

    /**
     * the plugin instance
     */
    private static GuildsBungee instance = null;

    @Override
    public void onEnable() {
        getProxy().registerChannel("Guilds");
        getProxy().getPluginManager().registerListener(this, this);
    }

    @EventHandler
    public void onPluginMessageReceived(PluginMessageEvent e) throws IOException {
        if (!e.getTag().equals("Guilds")) {
            return;
        }
        if (!(e.getSender() instanceof Server)) {
            return;
        }
        ByteArrayInputStream stream = new ByteArrayInputStream(e.getData());
        DataInputStream in = new DataInputStream(stream);
        Operation operation = Operation.valueOf(in.readUTF());
        String guild;
        UUID uuid;
        String message;

        switch (operation) {
            case SendGuildChannelBroadcast:
            case SendAllianceChannelBroadcast:
                String[] uuids = in.readUTF().split(Pattern.quote(","));
                message = in.readUTF();
                for (String uuidString : uuids) {
                    uuid = UUID.fromString(uuidString);
                    sendSingleMessage(uuid, message);
                }
                log(message);
                break;
            case SendMessage:
                uuid = UUID.fromString(in.readUTF());
                message = in.readUTF();
                sendSingleMessage(uuid, message);
                break;
            case SyncGuilds:
                makeServersSyncGuilds();
                break;
            case SyncGuild:
                Integer guildId = in.readInt();
                makeServersSyncGuild(guildId);
                break;
            case SyncAlliances:
                makeServersSyncAlliances();
                break;
            case SyncAlliance:
                Integer allianceId = in.readInt();
                makeServersSyncAlliance(allianceId);
                break;
            case SendPlayerToGuildHome:
                uuid = UUID.fromString(in.readUTF());
                guild = in.readUTF();
                String homeServer = in.readUTF();
                sendPlayerToGuildHome(uuid, guild, homeServer, (Server) e.getSender());
                break;
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
            out.writeUTF(isOnline ? "PlayerJoined" : "PlayerDisconnected");
            out.writeUTF(uuid.toString());
            serverInfo.sendData("Guilds", out.toByteArray());
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
                    out.writeUTF("HomeServerUnreachable");
                    out.writeUTF(uuid.toString());
                    source.sendData("Guilds", out.toByteArray());
                    return;
                }
            }
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("SendPlayerHome");
        out.writeUTF(uuid.toString());
        out.writeUTF(guild);
        if (target != null) {
            target.sendData("Guilds", out.toByteArray());
        }
    }

    /**
     * forces all servers to sync the guilds
     */
    protected static void makeServersSyncGuilds() {
        for (ServerInfo serverInfo : ProxyServer.getInstance().getServers().values()) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("syncGuilds");
            String players = "";
            for (ProxiedPlayer proxiedPlayer : ProxyServer.getInstance().getPlayers()) {
                players += proxiedPlayer.getUniqueId().toString()+" ";
            }
            out.writeUTF(players.trim());
            serverInfo.sendData("Guilds", out.toByteArray());
        }
    }

    /**
     * forces all servers to sync the guild
     *
     * @param guildId the guild id
     */
    protected static void makeServersSyncGuild(Integer guildId) {
        for (ServerInfo serverInfo : ProxyServer.getInstance().getServers().values()) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("syncGuild");
            out.writeInt(guildId);
            String players = "";
            for (ProxiedPlayer proxiedPlayer : ProxyServer.getInstance().getPlayers()) {
                players += proxiedPlayer.getUniqueId().toString()+" ";
            }
            out.writeUTF(players.trim());
            serverInfo.sendData("Guilds", out.toByteArray());
        }
    }

    /**
     * distributes a message to all servers that makes them reload the list of alliances
     */
    public static void makeServersSyncAlliances() {
        for (ServerInfo serverInfo : ProxyServer.getInstance().getServers().values()) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("syncAlliances");
            serverInfo.sendData("Guilds", out.toByteArray());
        }
    }

    /**
     * makes all servers reload the given alliance
     *
     * @param allianceId the alliance id
     */
    public static void makeServersSyncAlliance(Integer allianceId) {
        for (ServerInfo serverInfo : ProxyServer.getInstance().getServers().values()) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("syncAlliance");
            out.writeInt(allianceId);
            serverInfo.sendData("Guilds", out.toByteArray());
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

    public enum Operation {
        SendGuildChannelBroadcast, SendAllianceChannelBroadcast,
        SyncGuilds, SyncGuild, SyncAlliances, SyncAlliance,
        SendMessage, SendPlayerToGuildHome
    }

}
