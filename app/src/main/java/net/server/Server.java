/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.server;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import client.Character;
import client.Client;
import client.Family;
import client.SkillFactory;
import client.command.CommandsExecutor;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.CashIdGenerator;
import client.newyear.NewYearCardRecord;
import client.processor.npc.FredrickProcessor;
import config.LogBackConfig;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import constants.net.OpcodeConstants;
import constants.net.ServerConstants;
import database.note.NoteDao;
import net.ChannelDependencies;
import net.PacketProcessor;
import net.netty.LoginServer;
import net.packet.Packet;
import net.server.channel.Channel;
import net.server.coordinator.session.IpAddresses;
import net.server.coordinator.session.SessionCoordinator;
import net.server.guild.Alliance;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.task.*;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.CashShop.CashItemFactory;
import server.SkillbookInformationProvider;
import server.ThreadManager;
import server.TimerManager;
import server.expeditions.ExpeditionBossLog;
import server.life.PlayerNPC;
import server.quest.Quest;
import service.NoteService;
import tools.DatabaseConnection;
import tools.Pair;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.TimeUnit.*;

public class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private static Server instance = null;

    public static Server getInstance() {
        if (instance == null) {
            instance = new Server();
        }
        return instance;
    }

    public static Server getInstance(Context context) {
        if (instance == null) {
            instance = new Server(context);
            LogBackConfig.configure();
        }
        return instance;
    }

    private static final Set<Integer> activeFly = new HashSet<>();
    private static final Map<Integer, Integer> couponRates = new HashMap<>(30);
    private static final List<Integer> activeCoupons = new LinkedList<>();
    private static ChannelDependencies channelDependencies;

    private LoginServer loginServer;
    private final List<Map<Integer, String>> channels = new LinkedList<>();
    private final List<World> worlds = new ArrayList<>();
    private final Properties subnetInfo = new Properties();
    private final Map<Integer, Set<Integer>> accountChars = new HashMap<>();
    private final Map<Integer, Short> accountCharacterCount = new HashMap<>();
    private final Map<Integer, Integer> worldChars = new HashMap<>();
    private final Map<String, Integer> transitioningChars = new HashMap<>();
    private final List<Pair<Integer, String>> worldRecommendedList = new LinkedList<>();
    private final Map<Integer, Guild> guilds = new HashMap<>(100);
    private final Map<Client, Long> inLoginState = new HashMap<>(100);

    private final PlayerBuffStorage buffStorage = new PlayerBuffStorage();
    private final Map<Integer, Alliance> alliances = new HashMap<>(100);
    private final Map<Integer, NewYearCardRecord> newyears = new HashMap<>();
    private final List<Client> processDiseaseAnnouncePlayers = new LinkedList<>();
    private final List<Client> registeredDiseaseAnnouncePlayers = new LinkedList<>();

    private final List<List<Pair<String, Integer>>> playerRanking = new LinkedList<>();

    private final Lock srvLock = new ReentrantLock();
    private final Lock disLock = new ReentrantLock();

    private final Lock wldRLock;
    private final Lock wldWLock;

    private final Lock lgnRLock;
    private final Lock lgnWLock;

    private final AtomicLong currentTime = new AtomicLong(0);
    private long serverCurrentTime = 0;

    private volatile boolean availableDeveloperRoom = false;
    private boolean online = false;
    public static long uptime = System.currentTimeMillis();

    private Context context;

    private Server() {
        ReadWriteLock worldLock = new ReentrantReadWriteLock(true);
        this.wldRLock = worldLock.readLock();
        this.wldWLock = worldLock.writeLock();

        ReadWriteLock loginLock = new ReentrantReadWriteLock(true);
        this.lgnRLock = loginLock.readLock();
        this.lgnWLock = loginLock.writeLock();
    }

    private Server(Context context) {
        this.context = context;
        ReadWriteLock worldLock = new ReentrantReadWriteLock(true);
        this.wldRLock = worldLock.readLock();
        this.wldWLock = worldLock.writeLock();

        ReadWriteLock loginLock = new ReentrantReadWriteLock(true);
        this.lgnRLock = loginLock.readLock();
        this.lgnWLock = loginLock.writeLock();
    }

    public int getCurrentTimestamp() {
        return (int) (Server.getInstance().getCurrentTime() - Server.uptime);
    }

    public long getCurrentTime() {  // returns a slightly delayed time value, under frequency of UPDATE_INTERVAL
        return serverCurrentTime;
    }

    public void updateCurrentTime() {
        serverCurrentTime = currentTime.addAndGet(YamlConfig.config.server.UPDATE_INTERVAL);
    }

    public long forceUpdateCurrentTime() {
        long timeNow = System.currentTimeMillis();
        serverCurrentTime = timeNow;
        currentTime.set(timeNow);

        return timeNow;
    }

    public boolean isOnline() {
        return online;
    }

    public Context getContext() { return this.context; }

    public List<Pair<Integer, String>> worldRecommendedList() {
        return worldRecommendedList;
    }

    public void setNewYearCard(NewYearCardRecord nyc) {
        newyears.put(nyc.getId(), nyc);
    }

    public NewYearCardRecord getNewYearCard(int cardid) {
        return newyears.get(cardid);
    }

    public NewYearCardRecord removeNewYearCard(int cardid) {
        return newyears.remove(cardid);
    }

    public void setAvailableDeveloperRoom() {
        availableDeveloperRoom = true;
    }

    public boolean canEnterDeveloperRoom() {
        return availableDeveloperRoom;
    }

    private void loadPlayerNpcMapStepFromDb() {
        final List<World> wlist = this.getWorlds();
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.rawQuery("SELECT * FROM playernpcs_field", null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    int worldIdx = cursor.getColumnIndex("world");
                    int mapIdx = cursor.getColumnIndex("map");
                    int stepIdx = cursor.getColumnIndex("step");
                    int podiumIdx = cursor.getColumnIndex("podium");

                    if (worldIdx != -1 && mapIdx != -1 && stepIdx != -1 && podiumIdx != -1) {
                        int world = cursor.getInt(worldIdx);
                        int map = cursor.getInt(mapIdx);
                        int step = cursor.getInt(stepIdx);
                        int podium = cursor.getInt(podiumIdx);


                        World w = wlist.get(world);
                        if (w != null) {
                            w.setPlayerNpcMapData(map, step, podium);
                        }
                    }
                }
            }
        } catch (SQLiteException e) {
            log.error("loadPlayerNpcMapStepFromDb error", e);
        }
    }

    public World getWorld(int id) {
        wldRLock.lock();
        try {
            try {
                return worlds.get(id);
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        } finally {
            wldRLock.unlock();
        }
    }

    public List<World> getWorlds() {
        wldRLock.lock();
        try {
            return Collections.unmodifiableList(worlds);
        } finally {
            wldRLock.unlock();
        }
    }

    public int getWorldsSize() {
        wldRLock.lock();
        try {
            return worlds.size();
        } finally {
            wldRLock.unlock();
        }
    }

    public Channel getChannel(int world, int channel) {
        try {
            return this.getWorld(world).getChannel(channel);
        } catch (NullPointerException npe) {
            return null;
        }
    }

    public List<Channel> getChannelsFromWorld(int world) {
        try {
            return this.getWorld(world).getChannels();
        } catch (NullPointerException npe) {
            return new ArrayList<>(0);
        }
    }

    public List<Channel> getAllChannels() {
        try {
            List<Channel> channelz = new ArrayList<>();
            for (World world : this.getWorlds()) {
                channelz.addAll(world.getChannels());
            }
            return channelz;
        } catch (NullPointerException npe) {
            return new ArrayList<>(0);
        }
    }

    public Set<Integer> getOpenChannels(int world) {
        wldRLock.lock();
        try {
            return new HashSet<>(channels.get(world).keySet());
        } finally {
            wldRLock.unlock();
        }
    }

    private String getIP(int world, int channel) {
        wldRLock.lock();
        try {
            return channels.get(world).get(channel);
        } finally {
            wldRLock.unlock();
        }
    }

    public String[] getInetSocket(Client client, int world, int channel) {
        String remoteIp = client.getRemoteAddress();

        String[] hostAddress = getIP(world, channel).split(":");
        if (IpAddresses.isLocalAddress(remoteIp)) {
            hostAddress[0] = YamlConfig.config.server.LOCALHOST;
        } else if (IpAddresses.isLanAddress(remoteIp)) {
            hostAddress[0] = YamlConfig.config.server.LANHOST;
        }

        try {
            return hostAddress;
        } catch (Exception e) {
            return null;
        }
    }


    private void dumpData() {
        wldRLock.lock();
        try {
            log.debug("Worlds: {}", worlds);
            log.debug("Channels: {}", channels);
            log.debug("World recommended list: {}", worldRecommendedList);
            log.debug("---------------------");
        } finally {
            wldRLock.unlock();
        }
    }

    public int addChannel(int worldid) {
        World world;
        Map<Integer, String> channelInfo;
        int channelid;

        wldRLock.lock();
        try {
            if (worldid >= worlds.size()) {
                return -3;
            }

            channelInfo = channels.get(worldid);
            if (channelInfo == null) {
                return -3;
            }

            channelid = channelInfo.size();
            if (channelid >= YamlConfig.config.server.CHANNEL_SIZE) {
                return -2;
            }

            channelid++;
            world = this.getWorld(worldid);
        } finally {
            wldRLock.unlock();
        }

        Channel channel = new Channel(worldid, channelid, getCurrentTime(), this.context);
        channel.setServerMessage(YamlConfig.config.worlds.get(worldid).why_am_i_recommended);

        if (world.addChannel(channel)) {
            wldWLock.lock();
            try {
                channelInfo.put(channelid, channel.getIP());
            } finally {
                wldWLock.unlock();
            }
        }

        return channelid;
    }

    public int addWorld() {
        int newWorld = initWorld();
        if (newWorld > -1) {
            installWorldPlayerRanking(newWorld);

            Set<Integer> accounts;
            lgnRLock.lock();
            try {
                accounts = new HashSet<>(accountChars.keySet());
            } finally {
                lgnRLock.unlock();
            }

            for (Integer accId : accounts) {
                loadAccountCharactersView(accId, 0, newWorld);
            }
        }

        return newWorld;
    }

    private int initWorld() {
        int i;

        wldRLock.lock();
        try {
            i = worlds.size();

            if (i >= YamlConfig.config.server.WLDLIST_SIZE) {
                return -1;
            }
        } finally {
            wldRLock.unlock();
        }

        log.info("Starting world {}", i);

        int exprate = YamlConfig.config.worlds.get(i).exp_rate;
        int mesorate = YamlConfig.config.worlds.get(i).meso_rate;
        int droprate = YamlConfig.config.worlds.get(i).drop_rate;
        int bossdroprate = YamlConfig.config.worlds.get(i).boss_drop_rate;
        int questrate = YamlConfig.config.worlds.get(i).quest_rate;
        int travelrate = YamlConfig.config.worlds.get(i).travel_rate;
        int fishingrate = YamlConfig.config.worlds.get(i).fishing_rate;

        int flag = YamlConfig.config.worlds.get(i).flag;
        String event_message = YamlConfig.config.worlds.get(i).event_message;
        String why_am_i_recommended = YamlConfig.config.worlds.get(i).why_am_i_recommended;

        World world = new World(i,
                flag,
                event_message,
                exprate, droprate, bossdroprate, mesorate, questrate, travelrate, fishingrate, this.context);

        Map<Integer, String> channelInfo = new HashMap<>();
        long bootTime = getCurrentTime();
        for (int j = 1; j <= YamlConfig.config.worlds.get(i).channels; j++) {
            int channelid = j;
            Channel channel = new Channel(i, channelid, bootTime, this.context);

            world.addChannel(channel);
            channelInfo.put(channelid, channel.getIP());
        }

        boolean canDeploy;

        wldWLock.lock();    // thanks Ashen for noticing a deadlock issue when trying to deploy a channel
        try {
            canDeploy = world.getId() == worlds.size();
            if (canDeploy) {
                worldRecommendedList.add(new Pair<>(i, why_am_i_recommended));
                worlds.add(world);
                channels.add(i, channelInfo);
            }
        } finally {
            wldWLock.unlock();
        }

        if (canDeploy) {
            world.setServerMessage(YamlConfig.config.worlds.get(i).server_message);

            log.info("Finished loading world {}", i);
            return i;
        } else {
            log.error("Could not load world {}...", i);
            world.shutdown();
            return -2;
        }
    }

    public boolean removeChannel(int worldid) {   //lol don't!
        World world;

        wldRLock.lock();
        try {
            if (worldid >= worlds.size()) {
                return false;
            }
            world = worlds.get(worldid);
        } finally {
            wldRLock.unlock();
        }

        if (world != null) {
            int channel = world.removeChannel();
            wldWLock.lock();
            try {
                Map<Integer, String> m = channels.get(worldid);
                if (m != null) {
                    m.remove(channel);
                }
            } finally {
                wldWLock.unlock();
            }

            return channel > -1;
        }

        return false;
    }

    public boolean removeWorld() {   //lol don't!
        World w;
        int worldid;

        wldRLock.lock();
        try {
            worldid = worlds.size() - 1;
            if (worldid < 0) {
                return false;
            }

            w = worlds.get(worldid);
        } finally {
            wldRLock.unlock();
        }

        if (w == null || !w.canUninstall()) {
            return false;
        }

        removeWorldPlayerRanking();
        w.shutdown();

        wldWLock.lock();
        try {
            if (worldid == worlds.size() - 1) {
                worlds.remove(worldid);
                channels.remove(worldid);
                worldRecommendedList.remove(worldid);
            }
        } finally {
            wldWLock.unlock();
        }

        return true;
    }

    private void resetServerWorlds() {  // thanks maple006 for noticing proprietary lists assigned to null
        wldWLock.lock();
        try {
            worlds.clear();
            channels.clear();
            worldRecommendedList.clear();
        } finally {
            wldWLock.unlock();
        }
    }

    private static long getTimeLeftForNextHour() {
        Calendar nextHour = Calendar.getInstance();
        nextHour.add(Calendar.HOUR, 1);
        nextHour.set(Calendar.MINUTE, 0);
        nextHour.set(Calendar.SECOND, 0);

        return Math.max(0, nextHour.getTimeInMillis() - System.currentTimeMillis());
    }

    public static long getTimeLeftForNextDay() {
        Calendar nextDay = Calendar.getInstance();
        nextDay.add(Calendar.DAY_OF_MONTH, 1);
        nextDay.set(Calendar.HOUR_OF_DAY, 0);
        nextDay.set(Calendar.MINUTE, 0);
        nextDay.set(Calendar.SECOND, 0);

        return Math.max(0, nextDay.getTimeInMillis() - System.currentTimeMillis());
    }

    public Map<Integer, Integer> getCouponRates() {
        return couponRates;
    }

    public static void cleanNxcodeCoupons(SQLiteDatabase con) throws SQLiteException {
        if (!YamlConfig.config.server.USE_CLEAR_OUTDATED_COUPONS) {
            return;
        }

        long timeClear = System.currentTimeMillis() - DAYS.toMillis(14);

        String sqlInsertStatement = "SELECT * FROM nxcode WHERE expiration <= ?";
        String[] args = new String[]{String.valueOf(timeClear)};

        try (Cursor cursor = con.rawQuery(sqlInsertStatement, args)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String codeDelStatement = "DELETE FROM nxcode_items WHERE codeid = ?";
                    SQLiteStatement statement = con.compileStatement(codeDelStatement);
                    statement.bindString(1, String.valueOf(cursor.getColumnIndex("id")));
                    statement.executeUpdateDelete();
                } while (cursor.moveToNext());

                String expirationDelStatement = "DELETE FROM nxcode WHERE expiration <= ?";
                SQLiteStatement statement = con.compileStatement(expirationDelStatement);
                statement.bindLong(1, timeClear);
                statement.executeUpdateDelete();
            }
        }
    }

    private void loadCouponRates(SQLiteDatabase c) throws SQLiteException {
        String sqlInsertStatement = "SELECT couponid, rate FROM nxcoupons";

        try(Cursor cursor = c.rawQuery(sqlInsertStatement, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int couponIdIndex = cursor.getColumnIndex("couponid");
                    int rateIndex = cursor.getColumnIndex("rate");
                    if (couponIdIndex != -1 && rateIndex != -1) {
                        int cid = cursor.getInt(couponIdIndex);
                        int rate = cursor.getInt(rateIndex);
                        couponRates.put(cid, rate);
                    }
                } while (cursor.moveToNext());
            }
        }
    }

    public List<Integer> getActiveCoupons() {
        synchronized (activeCoupons) {
            return activeCoupons;
        }
    }

    public void commitActiveCoupons() {
        for (World world : getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!chr.isLoggedin()) {
                    continue;
                }

                chr.updateCouponRates();
            }
        }
    }

    public void toggleCoupon(Integer couponId) {
        if (ItemConstants.isRateCoupon(couponId)) {
            synchronized (activeCoupons) {
                if (activeCoupons.contains(couponId)) {
                    activeCoupons.remove(couponId);
                } else {
                    activeCoupons.add(couponId);
                }

                commitActiveCoupons();
            }
        }
    }

    public void updateActiveCoupons(SQLiteDatabase con) throws SQLiteException {
        synchronized (activeCoupons) {
            activeCoupons.clear();
            Calendar c = Calendar.getInstance();

            int weekDay = c.get(Calendar.DAY_OF_WEEK);
            int hourDay = c.get(Calendar.HOUR_OF_DAY);

            int weekdayMask = (1 << weekDay);
            String sqlInsertStatement = "SELECT couponid FROM nxcoupons WHERE (activeday & ?) = ? AND starthour <= ? AND endhour > ?";
            String[] selectionArgs = {String.valueOf(weekdayMask), String.valueOf(weekdayMask), String.valueOf(hourDay), String.valueOf(hourDay)};

            try (Cursor cursor = con.rawQuery(sqlInsertStatement, selectionArgs)) {
                while (cursor != null && cursor.moveToNext()) {
                    int couponIdIndex = cursor.getColumnIndex("couponid");
                    if (couponIdIndex != -1) {
                        int couponId = cursor.getInt(couponIdIndex);
                        activeCoupons.add(couponId);
                    }
                }
            }
        }
    }

    public void runAnnouncePlayerDiseasesSchedule() {
        List<Client> processDiseaseAnnounceClients;
        disLock.lock();
        try {
            processDiseaseAnnounceClients = new LinkedList<>(processDiseaseAnnouncePlayers);
            processDiseaseAnnouncePlayers.clear();
        } finally {
            disLock.unlock();
        }

        while (!processDiseaseAnnounceClients.isEmpty()) {
            Client c = processDiseaseAnnounceClients.remove(0);
            Character player = c.getPlayer();
            if (player != null && player.isLoggedinWorld()) {
                player.announceDiseases();
                player.collectDiseases();
            }
        }

        disLock.lock();
        try {
            // this is to force the system to wait for at least one complete tick before releasing disease info for the registered clients
            while (!registeredDiseaseAnnouncePlayers.isEmpty()) {
                Client c = registeredDiseaseAnnouncePlayers.remove(0);
                processDiseaseAnnouncePlayers.add(c);
            }
        } finally {
            disLock.unlock();
        }
    }

    public void registerAnnouncePlayerDiseases(Client c) {
        disLock.lock();
        try {
            registeredDiseaseAnnouncePlayers.add(c);
        } finally {
            disLock.unlock();
        }
    }

    public List<Pair<String, Integer>> getWorldPlayerRanking(int worldid) {
        wldRLock.lock();
        try {
            return new ArrayList<>(playerRanking.get(!YamlConfig.config.server.USE_WHOLE_SERVER_RANKING ? worldid : 0));
        } finally {
            wldRLock.unlock();
        }
    }

    private void installWorldPlayerRanking(int worldid) {
        List<Pair<Integer, List<Pair<String, Integer>>>> ranking = loadPlayerRankingFromDB(worldid);
        if (!ranking.isEmpty()) {
            wldWLock.lock();
            try {
                if (!YamlConfig.config.server.USE_WHOLE_SERVER_RANKING) {
                    for (int i = playerRanking.size(); i <= worldid; i++) {
                        playerRanking.add(new ArrayList<>(0));
                    }

                    playerRanking.add(worldid, ranking.get(0).getRight());
                } else {
                    playerRanking.add(0, ranking.get(0).getRight());
                }
            } finally {
                wldWLock.unlock();
            }
        }
    }

    private void removeWorldPlayerRanking() {
        if (!YamlConfig.config.server.USE_WHOLE_SERVER_RANKING) {
            wldWLock.lock();
            try {
                if (playerRanking.size() < worlds.size()) {
                    return;
                }

                playerRanking.remove(playerRanking.size() - 1);
            } finally {
                wldWLock.unlock();
            }
        } else {
            List<Pair<Integer, List<Pair<String, Integer>>>> ranking = loadPlayerRankingFromDB(-1 * (this.getWorldsSize() - 2));  // update ranking list

            wldWLock.lock();
            try {
                playerRanking.add(0, ranking.get(0).getRight());
            } finally {
                wldWLock.unlock();
            }
        }
    }

    public void updateWorldPlayerRanking() {
        List<Pair<Integer, List<Pair<String, Integer>>>> rankUpdates = loadPlayerRankingFromDB(-1 * (this.getWorldsSize() - 1));
        if (rankUpdates.isEmpty()) {
            return;
        }

        wldWLock.lock();
        try {
            if (!YamlConfig.config.server.USE_WHOLE_SERVER_RANKING) {
                for (int i = playerRanking.size(); i <= rankUpdates.get(rankUpdates.size() - 1).getLeft(); i++) {
                    playerRanking.add(new ArrayList<>(0));
                }

                for (Pair<Integer, List<Pair<String, Integer>>> wranks : rankUpdates) {
                    playerRanking.set(wranks.getLeft(), wranks.getRight());
                }
            } else {
                playerRanking.set(0, rankUpdates.get(0).getRight());
            }
        } finally {
            wldWLock.unlock();
        }

    }

    private void initWorldPlayerRanking() {
        if (YamlConfig.config.server.USE_WHOLE_SERVER_RANKING) {
            wldWLock.lock();
            try {
                playerRanking.add(new ArrayList<>(0));
            } finally {
                wldWLock.unlock();
            }
        }

        updateWorldPlayerRanking();
    }

    private List<Pair<Integer, List<Pair<String, Integer>>>> loadPlayerRankingFromDB(int worldid) {
        List<Pair<Integer, List<Pair<String, Integer>>>> rankSystem = new ArrayList<>();
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try {
            String worldQuery;
            if (!YamlConfig.config.server.USE_WHOLE_SERVER_RANKING) {
                if (worldid >= 0) {
                    worldQuery = (" AND characters.world = " + worldid);
                } else {
                    worldQuery = (" AND characters.world >= 0 AND characters.world <= " + -worldid);
                }
            } else {
                worldQuery = (" AND characters.world >= 0 AND characters.world <= " + Math.abs(worldid));
            }

            List<Pair<String, Integer>> rankUpdate = new ArrayList<>(0);
            try (Cursor cursor = con.rawQuery("SELECT characters.name, characters.level, characters.world FROM characters LEFT JOIN accounts ON accounts.id = characters.accountid WHERE characters.gm < 2 AND accounts.banned = '0'" + worldQuery + " ORDER BY " + (!YamlConfig.config.server.USE_WHOLE_SERVER_RANKING ? "world, " : "") + "level DESC, exp DESC, lastExpGainTime ASC LIMIT 50", null)) {
                if (cursor != null) {
                    if (!YamlConfig.config.server.USE_WHOLE_SERVER_RANKING) {
                        int currentWorld = -1;
                        while (cursor.moveToNext()) {
                            int worldIdx = cursor.getColumnIndex("world");
                            if (worldIdx != -1) {
                                int rsWorld = cursor.getInt(worldIdx);
                                if (currentWorld < rsWorld) {
                                    currentWorld = rsWorld;
                                    rankUpdate = new ArrayList<>(50);
                                    rankSystem.add(new Pair<>(rsWorld, rankUpdate));
                                }
                            }

                            int nameIdx = cursor.getColumnIndex("name");
                            int levelIdx = cursor.getColumnIndex("level");
                            if (nameIdx != -1 && levelIdx != -1) {
                                rankUpdate.add(new Pair<>(cursor.getString(nameIdx), cursor.getInt(levelIdx)));
                            }
                        }
                    } else {
                        rankUpdate = new ArrayList<>(50);
                        rankSystem.add(new Pair<>(0, rankUpdate));

                        while (cursor.moveToNext()) {
                            int nameIdx = cursor.getColumnIndex("name");
                            int levelIdx = cursor.getColumnIndex("level");
                            if (nameIdx != -1 && levelIdx != -1) {
                                rankUpdate.add(new Pair<>(cursor.getString(nameIdx), cursor.getInt(levelIdx)));
                            }
                        }
                    }
                }
            }
        } catch (SQLiteException ex) {
            log.error("loadPlayerRankingFromDB error", ex);
        }

        return rankSystem;
    }

    public void init() {
        Instant beforeInit = Instant.now();
        log.info("Cosmic v" + ServerConstants.VERSION + " starting up. ");
        if (this.context != null) {
            YamlConfig.config = YamlConfig.loadConfig(this.context);
        }
        if (YamlConfig.config.server.SHUTDOWNHOOK) {
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown(false)));
        }
        SQLiteDatabase db = DatabaseConnection.getConnection();
        channelDependencies = registerChannelDependencies();

        final ExecutorService initExecutor = Executors.newFixedThreadPool(10);
        // Run slow operations asynchronously to make startup faster
        final List<Future<?>> futures = new ArrayList<>();
        futures.add(initExecutor.submit(SkillFactory::loadAllSkills));
        futures.add(initExecutor.submit(CashItemFactory::loadAllCashItems));
        futures.add(initExecutor.submit(Quest::loadAllQuests));
        futures.add(initExecutor.submit(SkillbookInformationProvider::loadAllSkillbookInformation));
        initExecutor.shutdown();

        TimeZone.setDefault(TimeZone.getTimeZone(YamlConfig.config.server.TIMEZONE));

        final int worldCount = Math.min(GameConstants.WORLD_NAMES.length, YamlConfig.config.server.WORLDS);
        try {
            setAllLoggedOut(db);
            setAllMerchantsInactive(db);
            cleanNxcodeCoupons(db);
            loadCouponRates(db);
            updateActiveCoupons(db);
            NewYearCardRecord.startPendingNewYearCardRequests(db);
            CashIdGenerator.loadExistentCashIdsFromDb(db);
            applyAllNameChanges(db); // -- name changes can be missed by INSTANT_NAME_CHANGE --
            applyAllWorldTransfers(db);
            PlayerNPC.loadRunningRankData(db, worldCount);
        } catch (SQLiteException sqle) {
            log.error("Failed to run all startup-bound database tasks " + sqle);
            throw new IllegalStateException(sqle);
        }

        ThreadManager.getInstance().start();
        initializeTimelyTasks(channelDependencies);    // aggregated method for timely tasks thanks to lxconan

        try {
            for (int i = 0; i < worldCount; i++) {
                initWorld();
            }
            initWorldPlayerRanking();

            loadPlayerNpcMapStepFromDb();

            if (YamlConfig.config.server.USE_FAMILY_SYSTEM) {
                SQLiteDatabase con = DatabaseConnection.getConnection();
                Family.loadAllFamilies(con);
            }
        } catch (Exception e) {
            log.error("[SEVERE] Syntax error in 'world.ini'. " + e); //For those who get errors
            System.exit(0);
        }

        // Wait on all async tasks to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("Failed to run all startup-bound loading tasks" + e);
                throw new IllegalStateException(e);
            }
        }

        loginServer = initLoginServer(8484);

        log.info("Listening on port 8484");

        online = true;
        Duration initDuration = Duration.between(beforeInit, Instant.now());
        log.info("Cosmic is now online after " + initDuration.toMillis() + " ms");

        OpcodeConstants.generateOpcodeNames();
        CommandsExecutor.getInstance();

        for (Channel ch : this.getAllChannels()) {
            ch.reloadEventScriptManager();
        }
    }

    private ChannelDependencies registerChannelDependencies() {
        NoteService noteService = new NoteService(new NoteDao());
        FredrickProcessor fredrickProcessor = new FredrickProcessor(noteService);
        ChannelDependencies channelDependencies = new ChannelDependencies(noteService, fredrickProcessor);

        PacketProcessor.registerGameHandlerDependencies(channelDependencies);

        return channelDependencies;
    }

    private LoginServer initLoginServer(int port) {
        LoginServer loginServer = new LoginServer(port);
        loginServer.start();
        return loginServer;
    }

    private static void setAllLoggedOut(SQLiteDatabase con) {
        try {
            con.execSQL("UPDATE accounts SET loggedin = 0");
        } catch (SQLiteException e) {
            log.error("setAllLoggedOut error", e); // Handle the exception or log it as needed
        }
    }

    private static void setAllMerchantsInactive(SQLiteDatabase con) {
        try {
            con.execSQL("UPDATE characters SET HasMerchant = 0");
        } catch (SQLiteException e) {
            log.error("setAllMerchantsInactive error", e); // Handle the exception or log it as needed
        }
    }

    private void initializeTimelyTasks(ChannelDependencies channelDependencies) {
        TimerManager tMan = TimerManager.getInstance();
        tMan.start();
        tMan.register(tMan.purge(), YamlConfig.config.server.PURGING_INTERVAL);//Purging ftw...
        disconnectIdlesOnLoginTask(this.context);

        long timeLeft = getTimeLeftForNextHour();
        tMan.register(new CharacterDiseaseTask(), YamlConfig.config.server.UPDATE_INTERVAL, YamlConfig.config.server.UPDATE_INTERVAL);
        tMan.register(new CouponTask(), YamlConfig.config.server.COUPON_INTERVAL, timeLeft);
        tMan.register(new RankingCommandTask(), MINUTES.toMillis(5), MINUTES.toMillis(5));
        tMan.register(new RankingLoginTask(), YamlConfig.config.server.RANKING_INTERVAL, timeLeft);
        tMan.register(new LoginCoordinatorTask(), HOURS.toMillis(1), timeLeft);
        tMan.register(new EventRecallCoordinatorTask(), HOURS.toMillis(1), timeLeft);
        tMan.register(new LoginStorageTask(), MINUTES.toMillis(2), MINUTES.toMillis(2));
        tMan.register(new DueyFredrickTask(channelDependencies.fredrickProcessor()), HOURS.toMillis(1), timeLeft);
        tMan.register(new InvitationTask(), SECONDS.toMillis(30), SECONDS.toMillis(30));
        tMan.register(new RespawnTask(), YamlConfig.config.server.RESPAWN_INTERVAL, YamlConfig.config.server.RESPAWN_INTERVAL);

        timeLeft = getTimeLeftForNextDay();
        ExpeditionBossLog.resetBossLogTable();
        tMan.register(new BossLogTask(), DAYS.toMillis(1), timeLeft);
    }

    public static void main(String[] args) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        Server.getInstance().init();
    }

    public Properties getSubnetInfo() {
        return subnetInfo;
    }

    public Alliance getAlliance(int id) {
        synchronized (alliances) {
            if (alliances.containsKey(id)) {
                return alliances.get(id);
            }
            return null;
        }
    }

    public void addAlliance(int id, Alliance alliance) {
        synchronized (alliances) {
            if (!alliances.containsKey(id)) {
                alliances.put(id, alliance);
            }
        }
    }

    public void disbandAlliance(int id) {
        synchronized (alliances) {
            Alliance alliance = alliances.get(id);
            if (alliance != null) {
                for (Integer gid : alliance.getGuilds()) {
                    guilds.get(gid).setAllianceId(0);
                }
                alliances.remove(id);
            }
        }
    }

    public void allianceMessage(int id, Packet packet, int exception, int guildex) {
        Alliance alliance = alliances.get(id);
        if (alliance != null) {
            for (Integer gid : alliance.getGuilds()) {
                if (guildex == gid) {
                    continue;
                }
                Guild guild = guilds.get(gid);
                if (guild != null) {
                    guild.broadcast(packet, exception);
                }
            }
        }
    }

    public boolean addGuildtoAlliance(int aId, int guildId) {
        Alliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.addGuild(guildId);
            guilds.get(guildId).setAllianceId(aId);
            return true;
        }
        return false;
    }

    public boolean removeGuildFromAlliance(int aId, int guildId) {
        Alliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.removeGuild(guildId);
            guilds.get(guildId).setAllianceId(0);
            return true;
        }
        return false;
    }

    public boolean setAllianceRanks(int aId, String[] ranks) {
        Alliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.setRankTitle(ranks);
            return true;
        }
        return false;
    }

    public boolean setAllianceNotice(int aId, String notice) {
        Alliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.setNotice(notice);
            return true;
        }
        return false;
    }

    public boolean increaseAllianceCapacity(int aId, int inc) {
        Alliance alliance = alliances.get(aId);
        if (alliance != null) {
            alliance.increaseCapacity(inc);
            return true;
        }
        return false;
    }

    public int createGuild(int leaderId, String name) {
        return Guild.createGuild(leaderId, name);
    }

    public Guild getGuildByName(String name) {
        synchronized (guilds) {
            for (Guild mg : guilds.values()) {
                if (mg.getName().equalsIgnoreCase(name)) {
                    return mg;
                }
            }

            return null;
        }
    }

    public Guild getGuild(int id) {
        synchronized (guilds) {
            if (guilds.get(id) != null) {
                return guilds.get(id);
            }

            return null;
        }
    }

    public Guild getGuild(int id, int world) {
        return getGuild(id, world, null);
    }

    public Guild getGuild(int id, int world, Character mc) {
        synchronized (guilds) {
            Guild g = guilds.get(id);
            if (g != null) {
                return g;
            }

            g = new Guild(id, world);
            if (g.getId() == -1) {
                return null;
            }

            if (mc != null) {
                GuildCharacter mgc = g.getMGC(mc.getId());
                if (mgc != null) {
                    mc.setMGC(mgc);
                    mgc.setCharacter(mc);
                } else {
                    log.error("Could not find chr {} when loading guild {}", mc.getName(), id);
                }

                g.setOnline(mc.getId(), true, mc.getClient().getChannel());
            }

            guilds.put(id, g);
            return g;
        }
    }

    public void setGuildMemberOnline(Character mc, boolean bOnline, int channel) {
        Guild g = getGuild(mc.getGuildId(), mc.getWorld(), mc);
        g.setOnline(mc.getId(), bOnline, channel);
    }

    public int addGuildMember(GuildCharacter mgc, Character chr) {
        Guild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            return g.addGuildMember(mgc, chr);
        }
        return 0;
    }

    public boolean setGuildAllianceId(int gId, int aId) {
        Guild guild = guilds.get(gId);
        if (guild != null) {
            guild.setAllianceId(aId);
            return true;
        }
        return false;
    }

    public void resetAllianceGuildPlayersRank(int gId) {
        guilds.get(gId).resetAllianceGuildPlayersRank();
    }

    public void leaveGuild(GuildCharacter mgc) {
        Guild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            g.leaveGuild(mgc);
        }
    }

    public void guildChat(int gid, String name, int cid, String msg) {
        Guild g = guilds.get(gid);
        if (g != null) {
            g.guildChat(name, cid, msg);
        }
    }

    public void changeRank(int gid, int cid, int newRank) {
        Guild g = guilds.get(gid);
        if (g != null) {
            g.changeRank(cid, newRank);
        }
    }

    public void expelMember(GuildCharacter initiator, String name, int cid) {
        Guild g = guilds.get(initiator.getGuildId());
        if (g != null) {
            g.expelMember(initiator, name, cid, channelDependencies.noteService());
        }
    }

    public void setGuildNotice(int gid, String notice) {
        Guild g = guilds.get(gid);
        if (g != null) {
            g.setGuildNotice(notice);
        }
    }

    public void memberLevelJobUpdate(GuildCharacter mgc) {
        Guild g = guilds.get(mgc.getGuildId());
        if (g != null) {
            g.memberLevelJobUpdate(mgc);
        }
    }

    public void changeRankTitle(int gid, String[] ranks) {
        Guild g = guilds.get(gid);
        if (g != null) {
            g.changeRankTitle(ranks);
        }
    }

    public void setGuildEmblem(int gid, short bg, byte bgcolor, short logo, byte logocolor) {
        Guild g = guilds.get(gid);
        if (g != null) {
            g.setGuildEmblem(bg, bgcolor, logo, logocolor);
        }
    }

    public void disbandGuild(int gid) {
        synchronized (guilds) {
            Guild g = guilds.get(gid);
            g.disbandGuild();
            guilds.remove(gid);
        }
    }

    public boolean increaseGuildCapacity(int gid) {
        Guild g = guilds.get(gid);
        if (g != null) {
            return g.increaseCapacity();
        }
        return false;
    }

    public void gainGP(int gid, int amount) {
        Guild g = guilds.get(gid);
        if (g != null) {
            g.gainGP(amount);
        }
    }

    public void guildMessage(int gid, Packet packet) {
        guildMessage(gid, packet, -1);
    }

    public void guildMessage(int gid, Packet packet, int exception) {
        Guild g = guilds.get(gid);
        if (g != null) {
            g.broadcast(packet, exception);
        }
    }

    public PlayerBuffStorage getPlayerBuffStorage() {
        return buffStorage;
    }

    public void deleteGuildCharacter(Character mc) {
        setGuildMemberOnline(mc, false, (byte) -1);
        if (mc.getMGC().getGuildRank() > 1) {
            leaveGuild(mc.getMGC());
        } else {
            disbandGuild(mc.getMGC().getGuildId());
        }
    }

    public void deleteGuildCharacter(GuildCharacter mgc) {
        if (mgc.getCharacter() != null) {
            setGuildMemberOnline(mgc.getCharacter(), false, (byte) -1);
        }
        if (mgc.getGuildRank() > 1) {
            leaveGuild(mgc);
        } else {
            disbandGuild(mgc.getGuildId());
        }
    }

    public void reloadGuildCharacters(int world) {
        World worlda = getWorld(world);
        for (Character mc : worlda.getPlayerStorage().getAllCharacters()) {
            if (mc.getGuildId() > 0) {
                setGuildMemberOnline(mc, true, worlda.getId());
                memberLevelJobUpdate(mc.getMGC());
            }
        }
        worlda.reloadGuildSummary();
    }

    public void broadcastMessage(int world, Packet packet) {
        for (Channel ch : getChannelsFromWorld(world)) {
            ch.broadcastPacket(packet);
        }
    }

    public void broadcastGMMessage(int world, Packet packet) {
        for (Channel ch : getChannelsFromWorld(world)) {
            ch.broadcastGMPacket(packet);
        }
    }

    public boolean isGmOnline(int world) {
        for (Channel ch : getChannelsFromWorld(world)) {
            for (Character player : ch.getPlayerStorage().getAllCharacters()) {
                if (player.isGM()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void changeFly(Integer accountid, boolean canFly) {
        if (canFly) {
            activeFly.add(accountid);
        } else {
            activeFly.remove(accountid);
        }
    }

    public boolean canFly(Integer accountid) {
        return activeFly.contains(accountid);
    }

    public int getCharacterWorld(Integer chrid) {
        lgnRLock.lock();
        try {
            Integer worldid = worldChars.get(chrid);
            return worldid != null ? worldid : -1;
        } finally {
            lgnRLock.unlock();
        }
    }

    public boolean haveCharacterEntry(Integer accountid, Integer chrid) {
        lgnRLock.lock();
        try {
            Set<Integer> accChars = accountChars.get(accountid);
            return accChars.contains(chrid);
        } finally {
            lgnRLock.unlock();
        }
    }

    public short getAccountCharacterCount(Integer accountid) {
        lgnRLock.lock();
        try {
            return accountCharacterCount.get(accountid);
        } finally {
            lgnRLock.unlock();
        }
    }

    public short getAccountWorldCharacterCount(Integer accountid, Integer worldid) {
        lgnRLock.lock();
        try {
            short count = 0;

            for (Integer chr : accountChars.get(accountid)) {
                if (worldChars.get(chr).equals(worldid)) {
                    count++;
                }
            }

            return count;
        } finally {
            lgnRLock.unlock();
        }
    }

    private Set<Integer> getAccountCharacterEntries(Integer accountid) {
        lgnRLock.lock();
        try {
            return new HashSet<>(accountChars.get(accountid));
        } finally {
            lgnRLock.unlock();
        }
    }

    public void updateCharacterEntry(Character chr) {
        Character chrView = chr.generateCharacterEntry(this.context);

        lgnWLock.lock();
        try {
            World wserv = this.getWorld(chrView.getWorld());
            if (wserv != null) {
                wserv.registerAccountCharacterView(chrView.getAccountID(), chrView);
            }
        } finally {
            lgnWLock.unlock();
        }
    }

    public void createCharacterEntry(Character chr) {
        Integer accountid = chr.getAccountID(), chrid = chr.getId(), world = chr.getWorld();

        lgnWLock.lock();
        try {
            accountCharacterCount.put(accountid, (short) (accountCharacterCount.get(accountid) + 1));

            Set<Integer> accChars = accountChars.get(accountid);
            accChars.add(chrid);

            worldChars.put(chrid, world);

            Character chrView = chr.generateCharacterEntry(this.context);

            World wserv = this.getWorld(chrView.getWorld());
            if (wserv != null) {
                wserv.registerAccountCharacterView(chrView.getAccountID(), chrView);
            }
        } finally {
            lgnWLock.unlock();
        }
    }

    public void deleteCharacterEntry(Integer accountid, Integer chrid) {
        lgnWLock.lock();
        try {
            accountCharacterCount.put(accountid, (short) (accountCharacterCount.get(accountid) - 1));

            Set<Integer> accChars = accountChars.get(accountid);
            accChars.remove(chrid);

            Integer world = worldChars.remove(chrid);
            if (world != null) {
                World wserv = this.getWorld(world);
                if (wserv != null) {
                    wserv.unregisterAccountCharacterView(accountid, chrid);
                }
            }
        } finally {
            lgnWLock.unlock();
        }
    }

    public void transferWorldCharacterEntry(Character chr, Integer toWorld) { // used before setting the new worldid on the character object
        lgnWLock.lock();
        try {
            Integer chrid = chr.getId(), accountid = chr.getAccountID(), world = worldChars.get(chr.getId());
            if (world != null) {
                World wserv = this.getWorld(world);
                if (wserv != null) {
                    wserv.unregisterAccountCharacterView(accountid, chrid);
                }
            }

            worldChars.put(chrid, toWorld);

            Character chrView = chr.generateCharacterEntry(this.context);

            World wserv = this.getWorld(toWorld);
            if (wserv != null) {
                wserv.registerAccountCharacterView(chrView.getAccountID(), chrView);
            }
        } finally {
            lgnWLock.unlock();
        }
    }
    
    /*
    public void deleteAccountEntry(Integer accountid) { is this even a thing?
        lgnWLock.lock();
        try {
            accountCharacterCount.remove(accountid);
            accountChars.remove(accountid);
        } finally {
            lgnWLock.unlock();
        }
    
        for (World wserv : this.getWorlds()) {
            wserv.clearAccountCharacterView(accountid);
            wserv.unregisterAccountStorage(accountid);
        }
    }
    */

    public SortedMap<Integer, List<Character>> loadAccountCharlist(int accountId, int visibleWorlds) {
        List<World> worlds = this.getWorlds();
        if (worlds.size() > visibleWorlds) {
            worlds = worlds.subList(0, visibleWorlds);
        }

        SortedMap<Integer, List<Character>> worldChrs = new TreeMap<>();
        int chrTotal = 0;

        lgnRLock.lock();
        try {
            for (World world : worlds) {
                List<Character> chrs = world.getAccountCharactersView(accountId);
                if (chrs == null) {
                    if (!accountChars.containsKey(accountId)) {
                        accountCharacterCount.put(accountId, (short) 0);
                        accountChars.put(accountId, new HashSet<>());    // not advisable at all to write on the map on a read-protected environment
                    }                                                           // yet it's known there's no problem since no other point in the source does
                } else if (!chrs.isEmpty()) {                                  // this action.
                    worldChrs.put(world.getId(), chrs);
                }
            }
        } finally {
            lgnRLock.unlock();
        }

        return worldChrs;
    }

    private static Pair<Short, List<List<Character>>> loadAccountCharactersViewFromDb(int accId, int wlen) {
        short characterCount = 0;
        List<List<Character>> wchars = new ArrayList<>(wlen);
        for (int i = 0; i < wlen; i++) {
            wchars.add(i, new LinkedList<>());
        }

        List<Character> chars = new LinkedList<>();
        int curWorld = 0;
        try {
            List<Pair<Item, Integer>> accEquips = ItemFactory.loadEquippedItems(accId, true, true);
            Map<Integer, List<Item>> accPlayerEquips = new HashMap<>();

            for (Pair<Item, Integer> ae : accEquips) {
                List<Item> playerEquips = accPlayerEquips.get(ae.getRight());
                if (playerEquips == null) {
                    playerEquips = new LinkedList<>();
                    accPlayerEquips.put(ae.getRight(), playerEquips);
                }

                playerEquips.add(ae.getLeft());
            }

            String[] columns = {"id", "world"};
            SQLiteDatabase con = DatabaseConnection.getConnection();
            try (Cursor cursor = con.query("characters", columns, "accountid = ?",
                    new String[]{String.valueOf(accId)}, null, null, "world, id")) {
                while (cursor.moveToNext()) {
                    characterCount++;

                    int worldIdx = cursor.getColumnIndex("world");

                    int cworld = worldIdx != -1 ? cursor.getInt(worldIdx) : 0;
                    if (cworld >= wlen) {
                        continue;
                    }

                    if (cworld > curWorld) {
                        wchars.add(curWorld, chars);

                        curWorld = cworld;
                        chars = new LinkedList<>();
                    }

                    int idIdx = cursor.getColumnIndex("id");
                    int cid = idIdx != -1 ? cursor.getInt(idIdx) : 0;
                    chars.add(Character.loadCharacterEntryFromDB(cursor, accPlayerEquips.get(cid)));
                }
            }

            wchars.add(curWorld, chars);
        } catch (SQLiteException sqle) {
            log.error("loadAccountCharactersViewFromDb error", sqle);
        }

        return new Pair<>(characterCount, wchars);
    }

    public void loadAllAccountsCharactersView() {
        SQLiteDatabase con = DatabaseConnection.getConnection();
        try (Cursor cursor = con.rawQuery("SELECT id FROM accounts", null)) {
            while (cursor.moveToNext()) {
                int idIdx = cursor.getColumnIndex("id");
                if (idIdx != -1) {
                    int accountId = cursor.getInt(idIdx);
                    if (isFirstAccountLogin(accountId)) {
                        loadAccountCharactersView(accountId, 0, 0);
                    }
                }
            }
        } catch (SQLiteException se) {
            log.error("loadAllAccountsCharactersView error", se);
        }
    }

    private boolean isFirstAccountLogin(Integer accId) {
        lgnRLock.lock();
        try {
            return !accountChars.containsKey(accId);
        } finally {
            lgnRLock.unlock();
        }
    }

    private static void applyAllNameChanges(SQLiteDatabase con) throws SQLiteException {
        con.beginTransaction();
        try (Cursor cursor = con.rawQuery("SELECT * FROM namechanges WHERE completionTime IS NULL", null)) {
            List<Pair<String, String>> changedNames = new LinkedList<>(); //logging only

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int nameChangeIdIdx = cursor.getColumnIndex("id");
                    int characterIdIdx = cursor.getColumnIndex("characterId");
                    int oldNameIdx = cursor.getColumnIndex("old");
                    int newNameIdx = cursor.getColumnIndex("new");

                    if (nameChangeIdIdx != -1 && characterIdIdx != -1 && oldNameIdx != -1 && newNameIdx != -1) {

                        boolean success = Character.doNameChange(con, cursor.getInt(characterIdIdx), cursor.getString(oldNameIdx), cursor.getString(newNameIdx), cursor.getInt(nameChangeIdIdx));

                        if (success) {
                            con.setTransactionSuccessful();
                            changedNames.add(new Pair<>(cursor.getString(oldNameIdx), cursor.getString(newNameIdx)));
                        }
                    }
                } while (cursor.moveToNext());
            }
            //log
            for (Pair<String, String> namePair : changedNames) {
                log.info("Name change applied - from: \"{}\" to \"{}\"", namePair.getLeft(), namePair.getRight());
            }
        } catch (SQLiteException e) {
            log.warn("Failed to retrieve list of pending name changes", e);
            throw e;
        } finally {
            con.endTransaction();
        }
    }

    private static void applyAllWorldTransfers(SQLiteDatabase con) throws SQLiteException {
        con.beginTransaction();
        try (Cursor cursor = con.rawQuery("SELECT * FROM worldtransfers WHERE completionTime IS NULL", null)) {
            List<Integer> removedTransfers = new LinkedList<>();
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        int nameChangeIdIdx = cursor.getColumnIndex("id");
                        int characterIdIdx = cursor.getColumnIndex("characterId");
                        int oldWorldIdx = cursor.getColumnIndex("from");
                        int newWorldIdx = cursor.getColumnIndex("to");

                        if (nameChangeIdIdx != -1 && characterIdIdx != -1 && oldWorldIdx != -1 && newWorldIdx != -1) {
                            int nameChangeId = cursor.getInt(nameChangeIdIdx);
                            int characterId = cursor.getInt(characterIdIdx);
                            int oldWorld = cursor.getInt(oldWorldIdx);
                            int newWorld = cursor.getInt(newWorldIdx);
                            String reason = Character.checkWorldTransferEligibility(con, characterId, oldWorld, newWorld); //check if character is still eligible
                            if (reason != null) {
                                removedTransfers.add(nameChangeId);
                                log.info("World transfer canceled: chrId {}, reason {}", characterId, reason);
                                int deleteResult = con.delete("worldtransfers", "id = ?", new String[]{String.valueOf(nameChangeId)});
                                if (deleteResult <= 0) {
                                    log.error("Failed to delete world transfer for chrId {}", characterId);
                                }
                            }
                        }
                    } while (cursor.moveToNext());

                    List<Pair<Integer, Pair<Integer, Integer>>> worldTransfers = new LinkedList<>(); //logging only <charid, <oldWorld, newWorld>>
                    cursor.moveToFirst(); // Move cursor back to the first row
                    do {
                        int nameChangeIdIdx = cursor.getColumnIndex("id");
                        int nameChangeId = cursor.getInt(nameChangeIdIdx);

                        if (removedTransfers.contains(nameChangeId)) {
                            continue;
                        }
                        int characterIdIdx = cursor.getColumnIndex("characterId");
                        int oldWorldIdx = cursor.getColumnIndex("from");
                        int newWorldIdx = cursor.getColumnIndex("to");

                        int characterId = cursor.getInt(characterIdIdx);
                        int oldWorld = cursor.getInt(oldWorldIdx);
                        int newWorld = cursor.getInt(newWorldIdx);

                        boolean success = Character.doWorldTransfer(con, characterId, oldWorld, newWorld, nameChangeId);

                        if (!success) {
                            con.setTransactionSuccessful();
                        } else {
                            worldTransfers.add(new Pair<>(characterId, new Pair<>(oldWorld, newWorld)));
                        }
                    } while (cursor.moveToNext());

                    //log
                    for (Pair<Integer, Pair<Integer, Integer>> worldTransferPair : worldTransfers) {
                        int charId = worldTransferPair.getLeft();
                        int oldWorld = worldTransferPair.getRight().getLeft();
                        int newWorld = worldTransferPair.getRight().getRight();
                        log.info("World transfer applied - character id {} from world {} to world {}", charId, oldWorld, newWorld);
                    }
                }
                con.setTransactionSuccessful();
            } finally {
                con.endTransaction();
            }
        } catch (SQLiteException e) {
            log.warn("Failed to retrieve list of pending world transfers", e);
            throw e;
        }
    }

    public void loadAccountCharacters(Client c) {
        Integer accId = c.getAccID();
        if (!isFirstAccountLogin(accId)) {
            Set<Integer> accWorlds = new HashSet<>();

            lgnRLock.lock();
            try {
                for (Integer chrid : getAccountCharacterEntries(accId)) {
                    accWorlds.add(worldChars.get(chrid));
                }
            } finally {
                lgnRLock.unlock();
            }

            int gmLevel = 0;
            for (Integer aw : accWorlds) {
                World wserv = this.getWorld(aw);

                if (wserv != null) {
                    for (Character chr : wserv.getAllCharactersView()) {
                        if (gmLevel < chr.gmLevel()) {
                            gmLevel = chr.gmLevel();
                        }
                    }
                }
            }

            c.setGMLevel(gmLevel);
            return;
        }

        int gmLevel = loadAccountCharactersView(c.getAccID(), 0, 0);
        c.setGMLevel(gmLevel);
    }

    private int loadAccountCharactersView(Integer accId, int gmLevel, int fromWorldid) {    // returns the maximum gmLevel found
        List<World> wlist = this.getWorlds();
        Pair<Short, List<List<Character>>> accCharacters = loadAccountCharactersViewFromDb(accId, wlist.size());

        lgnWLock.lock();
        try {
            List<List<Character>> accChars = accCharacters.getRight();
            accountCharacterCount.put(accId, accCharacters.getLeft());

            Set<Integer> chars = accountChars.get(accId);
            if (chars == null) {
                chars = new HashSet<>(5);
            }

            for (int wid = fromWorldid; wid < wlist.size(); wid++) {
                World w = wlist.get(wid);
                List<Character> wchars = accChars.get(wid);
                w.loadAccountCharactersView(accId, wchars);

                for (Character chr : wchars) {
                    int cid = chr.getId();
                    if (gmLevel < chr.gmLevel()) {
                        gmLevel = chr.gmLevel();
                    }

                    chars.add(cid);
                    worldChars.put(cid, wid);
                }
            }

            accountChars.put(accId, chars);
        } finally {
            lgnWLock.unlock();
        }

        return gmLevel;
    }

    public void loadAccountStorages(Client c) {
        int accountId = c.getAccID();
        Set<Integer> accWorlds = new HashSet<>();
        lgnWLock.lock();
        try {
            Set<Integer> chars = accountChars.get(accountId);

            for (Integer cid : chars) {
                Integer worldid = worldChars.get(cid);
                if (worldid != null) {
                    accWorlds.add(worldid);
                }
            }
        } finally {
            lgnWLock.unlock();
        }

        List<World> worldList = this.getWorlds();
        for (Integer worldid : accWorlds) {
            if (worldid < worldList.size()) {
                World wserv = worldList.get(worldid);
                wserv.loadAccountStorage(accountId);
            }
        }
    }

    private static String getRemoteHost(Client client) {
        return SessionCoordinator.getSessionRemoteHost(client);
    }

    public void setCharacteridInTransition(Client client, int charId) {
        String remoteIp = getRemoteHost(client);

        lgnWLock.lock();
        try {
            transitioningChars.put(remoteIp, charId);
        } finally {
            lgnWLock.unlock();
        }
    }

    public boolean validateCharacteridInTransition(Client client, int charId) {
        if (!YamlConfig.config.server.USE_IP_VALIDATION) {
            return true;
        }

        String remoteIp = getRemoteHost(client);

        lgnWLock.lock();
        try {
            Integer cid = transitioningChars.remove(remoteIp);
            return cid != null && cid.equals(charId);
        } finally {
            lgnWLock.unlock();
        }
    }

    public Integer freeCharacteridInTransition(Client client) {
        if (!YamlConfig.config.server.USE_IP_VALIDATION) {
            return null;
        }

        String remoteIp = getRemoteHost(client);

        lgnWLock.lock();
        try {
            return transitioningChars.remove(remoteIp);
        } finally {
            lgnWLock.unlock();
        }
    }

    public boolean hasCharacteridInTransition(Client client) {
        if (!YamlConfig.config.server.USE_IP_VALIDATION) {
            return true;
        }

        String remoteIp = getRemoteHost(client);

        lgnRLock.lock();
        try {
            return transitioningChars.containsKey(remoteIp);
        } finally {
            lgnRLock.unlock();
        }
    }

    public void registerLoginState(Client c) {
        srvLock.lock();
        try {
            inLoginState.put(c, System.currentTimeMillis() + 600000);
        } finally {
            srvLock.unlock();
        }
    }

    public void unregisterLoginState(Client c) {
        srvLock.lock();
        try {
            inLoginState.remove(c);
        } finally {
            srvLock.unlock();
        }
    }

    private void disconnectIdlesOnLoginState(Context context) {
        List<Client> toDisconnect = new LinkedList<>();

        srvLock.lock();
        try {
            long timeNow = System.currentTimeMillis();

            for (Entry<Client, Long> mc : inLoginState.entrySet()) {
                if (timeNow > mc.getValue()) {
                    toDisconnect.add(mc.getKey());
                }
            }

            for (Client c : toDisconnect) {
                inLoginState.remove(c);
            }
        } finally {
            srvLock.unlock();
        }

        for (Client c : toDisconnect) {    // thanks Lei for pointing a deadlock issue with srvLock
            if (c.isLoggedIn()) {
                c.disconnect(false, false);
            } else {
                SessionCoordinator.getInstance().closeSession(c, true);
            }
        }
    }

    private void disconnectIdlesOnLoginTask(Context context) {
        TimerManager.getInstance().register(() -> disconnectIdlesOnLoginState(context), 300000);
    }

    public final Runnable shutdown(final boolean restart) {//no player should be online when trying to shutdown!
        return () -> shutdownInternal(restart);
    }

    private synchronized void shutdownInternal(boolean restart) {
        log.info("{} the server!", restart ? "Restarting" : "Shutting down");
        if (getWorlds() == null) {
            return;//already shutdown
        }
        for (World w : getWorlds()) {
            w.shutdown();
        }

        List<Channel> allChannels = getAllChannels();

        for (Channel ch : allChannels) {
            while (!ch.finishedShutdown()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    log.error("Error during shutdown sleep", ie);
                }
            }
        }

        resetServerWorlds();

        ThreadManager.getInstance().stop();
        TimerManager.getInstance().purge();
        TimerManager.getInstance().stop();

        log.info("Worlds and channels are offline.");
        loginServer.stop();
        online = false;
        if (!restart) {  // shutdown hook deadlocks if System.exit() method is used within its body chores, thanks MIKE for pointing that out
            // We disabled log4j's shutdown hook in the config file, so we have to manually shut it down here,
            // after our last log statement.
            new Thread(() -> System.exit(0)).start();
        } else {
            log.info("Restarting the server...");
            instance = null;
            // TO DO
            getInstance().init();//DID I DO EVERYTHING?! D:
        }
    }
}
