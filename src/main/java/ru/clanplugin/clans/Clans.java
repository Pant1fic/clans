package ru.clanplugin.clans;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;

import java.awt.*;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.*;

public class Clans extends JavaPlugin implements Listener {

    private final Map<String, Clan> clans = new HashMap<>();
    private final Map<UUID, String> playerClan = new HashMap<>();
    private final Map<UUID, String> invites = new HashMap<>();
    private final Map<UUID, Map<String, QuestProgress>> playerQuests = new HashMap<>();
    private final Map<String, DailyQuest> dailyQuests = new LinkedHashMap<>();
    private final Map<String, WeeklyQuest> weeklyQuests = new LinkedHashMap<>();
    private Object economy = null;
    private File dataFile;

    private int maxMembers;
    private int tokensForJoin;
    private int homeTeleportDelay;

    private int currentDay = -1;
    private int currentWeek = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        loadData();

        if (!setupEconomy()) {
            getLogger().warning("Vault не найден!");
        }

        String[] cmds = {"clan", "claninvite", "clandesc", "clancreate", "csethome", "cspawn", "clandisband", "clankick", "clanrole"};
        for (String cmd : cmds) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        regenerateDailyQuests();
        regenerateWeeklyQuests();
        currentDay = LocalDate.now().getDayOfYear();
        currentWeek = currentDay / 7;

        new BukkitRunnable() {
            @Override
            public void run() {
                checkQuestReset();
            }
        }.runTaskTimer(this, 1200L, 1200L);

        getLogger().info("Clans v1.1 by l1nd3mann запущен! Макс. участников: " + maxMembers + " | Кланов загружено: " + clans.size());
    }

    @Override
    public void onDisable() {
        saveData();
        getLogger().info("Clans выключен. Кланов сохранено: " + clans.size());
    }

    private void loadConfig() {
        maxMembers = getConfig().getInt("clans.max-members", 10);
        tokensForJoin = getConfig().getInt("clans.tokens-for-join", 5);
        homeTeleportDelay = getConfig().getInt("clans.home-teleport-delay", 0);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (Exception ignored) {}
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

        if (data.contains("clans")) {
            for (String clanName : data.getConfigurationSection("clans").getKeys(false)) {
                String ownerStr = data.getString("clans." + clanName + ".owner");
                UUID owner = UUID.fromString(ownerStr);
                Clan clan = new Clan(clanName, owner);
                clan.description = data.getString("clans." + clanName + ".description", "Без описания");
                clan.tokens = data.getInt("clans." + clanName + ".tokens", 0);
                clan.maxMembers = maxMembers;

                List<String> membersStr = data.getStringList("clans." + clanName + ".members");
                for (String s : membersStr) {
                    String[] parts = s.split(":");
                    UUID muuid = UUID.fromString(parts[0]);
                    String role = parts.length > 1 ? parts[1] : "Участник";
                    clan.members.add(muuid);
                    clan.roles.put(muuid, role);
                }

                if (data.contains("clans." + clanName + ".home")) {
                    clan.home = data.getLocation("clans." + clanName + ".home");
                }

                clans.put(clanName, clan);

                for (UUID muuid : clan.members) {
                    playerClan.put(muuid, clanName);
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration data = new YamlConfiguration();

        for (Map.Entry<String, Clan> entry : clans.entrySet()) {
            String name = entry.getKey();
            Clan clan = entry.getValue();
            data.set("clans." + name + ".owner", clan.owner.toString());
            data.set("clans." + name + ".description", clan.description);
            data.set("clans." + name + ".tokens", clan.tokens);

            List<String> membersStr = new ArrayList<>();
            for (UUID muuid : clan.members) {
                String role = clan.roles.getOrDefault(muuid, "Участник");
                membersStr.add(muuid.toString() + ":" + role);
            }
            data.set("clans." + name + ".members", membersStr);

            if (clan.home != null) {
                data.set("clans." + name + ".home", clan.home);
            }
        }

        try {
            data.save(dataFile);
        } catch (Exception e) {
            getLogger().severe("Ошибка сохранения: " + e.getMessage());
        }
    }

    private boolean setupEconomy() {
        try {
            if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
            RegisteredServiceProvider<?> rsp = getServer().getServicesManager().getRegistration(Class.forName("net.milkbowl.vault.economy.Economy"));
            if (rsp == null) return false;
            economy = rsp.getProvider();
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String gradient(String text, String startHex, String endHex) {
        Color start = Color.decode(startHex);
        Color end = Color.decode(endHex);
        StringBuilder result = new StringBuilder();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            double ratio = (double) i / Math.max(length - 1, 1);
            int red = (int) (start.getRed() + (end.getRed() - start.getRed()) * ratio);
            int green = (int) (start.getGreen() + (end.getGreen() - start.getGreen()) * ratio);
            int blue = (int) (start.getBlue() + (end.getBlue() - start.getBlue()) * ratio);
            result.append(ChatColor.of(new Color(red, green, blue))).append(text.charAt(i));
        }
        return result.toString();
    }

    private void regenerateDailyQuests() {
        dailyQuests.clear();
        List<DailyQuest> pool = new ArrayList<>(List.of(
                new DailyQuest("Сруби 10 дуба", QuestTrigger.BREAK, Material.OAK_LOG, 10, 10, 0),
                new DailyQuest("Убей 20 криперов", QuestTrigger.KILL, EntityType.CREEPER, 20, 15, 0),
                new DailyQuest("Добудь 12 булыжника", QuestTrigger.BREAK, Material.COBBLESTONE, 12, 10, 0),
                new DailyQuest("Изготовь зелье силы", QuestTrigger.CRAFT_POTION, PotionType.STRENGTH, 1, 10, 500),
                new DailyQuest("Съешь 5 хлеба", QuestTrigger.EAT, Material.BREAD, 5, 8, 0),
                new DailyQuest("Убей 10 скелетов", QuestTrigger.KILL, EntityType.SKELETON, 10, 12, 0)
        ));
        Collections.shuffle(pool);
        for (int i = 0; i < Math.min(4, pool.size()); i++) {
            DailyQuest q = pool.get(i);
            dailyQuests.put(q.name, q);
        }
    }

    private void regenerateWeeklyQuests() {
        weeklyQuests.clear();
        List<WeeklyQuest> pool = new ArrayList<>(List.of(
                new WeeklyQuest("Убей 40 коров", QuestTrigger.KILL, EntityType.COW, 40, 30, 0),
                new WeeklyQuest("Съешь 10 свинины", QuestTrigger.EAT, Material.COOKED_PORKCHOP, 10, 20, 0),
                new WeeklyQuest("Добудь 50 булыжника", QuestTrigger.BREAK, Material.COBBLESTONE, 50, 25, 0),
                new WeeklyQuest("Сготовь 10 зелий регенерации", QuestTrigger.CRAFT_POTION, PotionType.REGENERATION, 10, 40, 1000),
                new WeeklyQuest("Сруби 30 берёзы", QuestTrigger.BREAK, Material.BIRCH_LOG, 30, 20, 0),
                new WeeklyQuest("Убей 15 эндерменов", QuestTrigger.KILL, EntityType.ENDERMAN, 15, 35, 0),
                new WeeklyQuest("Съешь 15 тыквенного пирога", QuestTrigger.EAT, Material.PUMPKIN_PIE, 15, 25, 500)
        ));
        Collections.shuffle(pool);
        for (int i = 0; i < Math.min(5, pool.size()); i++) {
            WeeklyQuest q = pool.get(i);
            weeklyQuests.put(q.name, q);
        }
    }

    private void checkQuestReset() {
        int newDay = LocalDate.now().getDayOfYear();
        int newWeek = newDay / 7;
        if (newDay != currentDay) {
            currentDay = newDay;
            playerQuests.values().forEach(m -> m.keySet().removeIf(dailyQuests::containsKey));
            regenerateDailyQuests();
            Bukkit.broadcastMessage("§6[Кланы] §eЕжедневные квесты обновлены!");
        }
        if (newWeek != currentWeek) {
            currentWeek = newWeek;
            playerQuests.values().forEach(m -> m.keySet().removeIf(weeklyQuests::containsKey));
            regenerateWeeklyQuests();
            Bukkit.broadcastMessage("§6[Кланы] §eЕженедельные квесты обновлены!");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (cmd.getName().equalsIgnoreCase("clan")) {
            if (args.length == 0) {
                openMainMenu(p);
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "help" -> {
                    p.sendMessage("§6===== " + gradient("Clans Help", "#FFD700", "#FF8C00") + " §6=====");
                    p.sendMessage("§e/clan §7- Открыть главное меню");
                    p.sendMessage("§e/clan help §7- Помощь по командам");
                    p.sendMessage("§e/clan version §7- Версия плагина");
                    p.sendMessage("§e/clancreate <название> §7- Создать клан");
                    p.sendMessage("§e/claninvite <игрок> §7- Пригласить в клан");
                    p.sendMessage("§e/clandesc <описание> §7- Изменить описание клана");
                    p.sendMessage("§e/csethome §7- Установить дом клана");
                    p.sendMessage("§e/cspawn §7- Телепортироваться на дом клана");
                    p.sendMessage("§e/clankick <игрок> §7- Выгнать из клана");
                    p.sendMessage("§e/clanrole <игрок> <роль> §7- Сменить роль (Участник/Модератор/Глава)");
                    p.sendMessage("§e/clandisband §7- Удалить клан");
                    p.sendMessage("§6======================================");
                }
                case "version" -> p.sendMessage(gradient("Clans v1.1 by l1nd3mann", "#FFD700", "#FF8C00"));
                default -> openMainMenu(p);
            }
            return true;
        }

        switch (cmd.getName().toLowerCase()) {
            case "clancreate" -> createClan(p, args.length > 0 ? String.join(" ", args) : null);
            case "claninvite" -> invitePlayer(p, args.length > 0 ? args[0] : null);
            case "clandesc" -> setDescription(p, args.length > 0 ? String.join(" ", args) : null);
            case "csethome" -> setClanHome(p);
            case "cspawn" -> teleportToClanHome(p);
            case "clandisband" -> disbandClan(p);
            case "clankick" -> kickMember(p, args.length > 0 ? args[0] : null);
            case "clanrole" -> setRole(p, args.length > 1 ? args[0] : null, args.length > 1 ? args[1] : null);
        }
        return true;
    }

    private void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§6Меню клана");
        UUID uuid = p.getUniqueId();
        String clanName = playerClan.get(uuid);

        if (clanName == null) {
            inv.setItem(20, createItem(Material.EMERALD, "§aСоздать клан", "§7/clancreate <название>"));
            if (invites.containsKey(uuid)) inv.setItem(22, createItem(Material.PAPER, "§eПринять приглашение", "§7От: " + invites.get(uuid)));
            inv.setItem(24, createItem(Material.GOLD_INGOT, "§bТоп кланов"));
            inv.setItem(49, createItem(Material.BARRIER, "§cЗакрыть"));
        } else {
            Clan clan = clans.get(clanName);
            if (clan == null) { playerClan.remove(uuid); return; }
            String role = clan.roles.getOrDefault(uuid, "Участник");
            boolean isMod = role.equals("Модератор") || role.equals("Глава");
            inv.setItem(0, createItem(Material.NAME_TAG, gradient(clan.name, "#FFD700", "#FF8C00"),
                    "§7Токены: §e" + clan.tokens,
                    "§7Участников: §a" + clan.members.size() + "/" + maxMembers,
                    "§7Роль: §d" + role));
            inv.setItem(1, createItem(Material.BOOK, "§eОписание", "§7" + clan.description));
            if (isMod) {
                inv.setItem(2, createItem(Material.WRITABLE_BOOK, "§6Изменить описание", "§7/clandesc <текст>"));
                inv.setItem(3, createItem(Material.PLAYER_HEAD, "§aПригласить", "§7/claninvite <ник>"));
            }
            inv.setItem(5, createItem(Material.CHEST, "§dЕжедневные квесты"));
            inv.setItem(6, createItem(Material.ENDER_CHEST, "§5Еженедельные квесты"));
            int slot = 19;
            for (UUID m : clan.members) {
                if (slot > 44) break;
                OfflinePlayer off = Bukkit.getOfflinePlayer(m);
                inv.setItem(slot++, createPlayerHead(off.getName(), "§a" + off.getName(), "§7Роль: §d" + clan.roles.getOrDefault(m, "Участник")));
            }
            inv.setItem(46, createItem(Material.RED_BED, "§6Дом", "§7/csethome | /cspawn"));
            inv.setItem(48, createItem(Material.REDSTONE, "§cПокинуть"));
            inv.setItem(50, createItem(Material.FLINT_AND_STEEL, "§4Удалить"));
            inv.setItem(49, createItem(Material.GOLD_INGOT, "§bТоп"));
        }
        p.openInventory(inv);
    }

    private void openDailyQuestsMenu(Player p) { openQuestMenu(p, dailyQuests, "§6Ежедневные квесты", "§e"); }
    private void openWeeklyQuestsMenu(Player p) { openQuestMenu(p, weeklyQuests, "§5Еженедельные квесты", "§d"); }

    private void openQuestMenu(Player p, Map<String, ? extends BaseQuest> quests, String title, String color) {
        Inventory inv = Bukkit.createInventory(null, 27, title);
        UUID uuid = p.getUniqueId();
        int slot = 0;
        for (Map.Entry<String, ? extends BaseQuest> e : quests.entrySet()) {
            if (slot >= 22) break;
            BaseQuest q = e.getValue();
            int prog = playerQuests.getOrDefault(uuid, new HashMap<>()).getOrDefault(e.getKey(), new QuestProgress()).progress;
            boolean done = prog >= q.target;
            inv.setItem(slot++, createItem(done ? Material.LIME_DYE : q.getIconMaterial(), color + q.name,
                    done ? "§a✔ Выполнено" : "§7Прогресс: " + prog + "/" + q.target,
                    "§7Токены: §6" + q.tokenReward,
                    q.moneyRequired > 0 ? "§7Требуется: §2$" + q.moneyRequired : ""));
        }
        inv.setItem(26, createItem(Material.BARRIER, "§cНазад", "§7Вернуться в главное меню"));
        p.openInventory(inv);
    }

    private void openTopMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, "§6Топ кланов");
        List<Clan> sorted = new ArrayList<>(clans.values());
        sorted.sort((a, b) -> Integer.compare(b.tokens, a.tokens));
        for (int i = 0; i < Math.min(26, sorted.size()); i++) {
            Clan c = sorted.get(i);
            inv.setItem(i, createItem(Material.GOLD_INGOT, "§6#" + (i + 1) + " " + c.name, "§7Токены: §e" + c.tokens, "§7Участников: §a" + c.members.size()));
        }
        inv.setItem(26, createItem(Material.BARRIER, "§cНазад", "§7Вернуться в главное меню"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        String t = e.getView().getTitle();
        if (!t.startsWith("§6") && !t.startsWith("§5")) return;
        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        ItemStack i = e.getCurrentItem();
        if (i == null) return;

        if (i.getType() == Material.BARRIER && i.getItemMeta().getDisplayName().contains("Назад")) {
            openMainMenu(p);
            return;
        }

        if (t.equals("§6Меню клана")) {
            switch (i.getType()) {
                case EMERALD -> p.sendMessage("§6/clancreate <название>");
                case PAPER -> acceptInvite(p);
                case CHEST -> openDailyQuestsMenu(p);
                case ENDER_CHEST -> openWeeklyQuestsMenu(p);
                case GOLD_INGOT -> openTopMenu(p);
                case REDSTONE -> leaveClan(p);
                case FLINT_AND_STEEL -> disbandClan(p);
                case BARRIER -> p.closeInventory();
                case WRITABLE_BOOK -> p.sendMessage("§6/clandesc <текст>");
                case PLAYER_HEAD -> p.sendMessage("§6/claninvite <ник>");
            }
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (playerClan.get(p.getUniqueId()) == null) return;
        checkProgress(p, QuestTrigger.BREAK, e.getBlock().getType(), 1);
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (e.getEntity().getKiller() == null) return;
        Player p = e.getEntity().getKiller();
        if (playerClan.get(p.getUniqueId()) == null) return;
        checkProgress(p, QuestTrigger.KILL, e.getEntityType(), 1);
    }

    @EventHandler
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (playerClan.get(p.getUniqueId()) == null) return;
        checkProgress(p, QuestTrigger.EAT, e.getItem().getType(), 1);
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (playerClan.get(p.getUniqueId()) == null) return;
        ItemStack r = e.getRecipe().getResult();
        if (r.getType().name().contains("POTION")) {
            PotionMeta pm = (PotionMeta) r.getItemMeta();
            checkProgress(p, QuestTrigger.CRAFT_POTION, pm.getBasePotionType(), 1);
        } else {
            checkProgress(p, QuestTrigger.CRAFT, r.getType(), 1);
        }
    }

    private void checkProgress(Player p, QuestTrigger trigger, Object val, int amount) {
        UUID u = p.getUniqueId();
        playerQuests.putIfAbsent(u, new HashMap<>());
        Map<String, QuestProgress> pq = playerQuests.get(u);
        for (var e : dailyQuests.entrySet()) if (match(e.getValue(), trigger, val)) addProgress(p, pq, e.getKey(), e.getValue(), amount);
        for (var e : weeklyQuests.entrySet()) if (match(e.getValue(), trigger, val)) addProgress(p, pq, e.getKey(), e.getValue(), amount);
    }

    private boolean match(BaseQuest q, QuestTrigger t, Object v) {
        if (q.trigger != t) return false;
        return switch (t) {
            case BREAK, EAT, CRAFT -> q.material == v;
            case KILL -> q.entityType == v;
            case CRAFT_POTION -> q.potionType == v;
            case HAVE_MONEY -> true;
        };
    }

    private void addProgress(Player p, Map<String, QuestProgress> pq, String name, BaseQuest q, int amount) {
        QuestProgress qp = pq.computeIfAbsent(name, k -> new QuestProgress());
        if (qp.progress >= q.target) return;

        if (q.trigger == QuestTrigger.HAVE_MONEY) {
            if (economy == null) return;
            try {
                double balance = (double) economy.getClass().getMethod("getBalance", OfflinePlayer.class).invoke(economy, p);
                if (balance >= q.moneyRequired) qp.progress = q.target;
                else return;
            } catch (Exception ex) { return; }
        } else {
            qp.progress += amount;
        }

        if (qp.progress >= q.target) {
            Clan clan = clans.get(playerClan.get(p.getUniqueId()));
            if (clan != null) {
                clan.tokens += q.tokenReward;
                p.sendMessage("§6✔ '" + name + "' выполнено! +" + q.tokenReward + " токенов");
                if (q.trigger == QuestTrigger.HAVE_MONEY && economy != null) {
                    try {
                        economy.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(economy, p, (double) q.moneyRequired);
                        p.sendMessage("§c  -$" + q.moneyRequired);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void createClan(Player p, String name) {
        if (name == null) { p.sendMessage("§c/clancreate <название>"); return; }
        UUID u = p.getUniqueId();
        if (playerClan.containsKey(u)) { p.sendMessage("§cТы уже в клане!"); return; }
        if (clans.containsKey(name)) { p.sendMessage("§cКлан существует!"); return; }
        Clan c = new Clan(name, u);
        c.maxMembers = maxMembers;
        c.roles.put(u, "Глава");
        clans.put(name, c);
        playerClan.put(u, name);
        saveData();
        p.sendMessage("§aКлан '" + name + "' создан!");
    }

    private void invitePlayer(Player p, String t) {
        if (t == null) { p.sendMessage("§c/claninvite <ник>"); return; }
        String cn = playerClan.get(p.getUniqueId());
        if (cn == null) return;
        Clan c = clans.get(cn);
        if (c == null) return;
        if (!c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Глава") && !c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Модератор")) { p.sendMessage("§cНет прав!"); return; }
        if (c.members.size() >= maxMembers) { p.sendMessage("§cКлан заполнен!"); return; }
        Player target = Bukkit.getPlayer(t);
        if (target == null) { p.sendMessage("§cИгрок не найден"); return; }
        invites.put(target.getUniqueId(), cn);
        p.sendMessage("§aПриглашение отправлено " + t);
        target.sendMessage("§eПриглашение в '" + cn + "'. /clan чтобы принять.");
    }

    private void setDescription(Player p, String d) {
        if (d == null) return;
        Clan c = clans.get(playerClan.get(p.getUniqueId()));
        if (c == null || (!c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Глава") && !c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Модератор"))) { p.sendMessage("§cНет прав!"); return; }
        c.description = d;
        saveData();
        p.sendMessage("§aОписание обновлено!");
    }

    private void setClanHome(Player p) {
        Clan c = clans.get(playerClan.get(p.getUniqueId()));
        if (c == null || (!c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Глава") && !c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Модератор"))) { p.sendMessage("§cНет прав!"); return; }
        c.home = p.getLocation();
        saveData();
        p.sendMessage("§aДом клана установлен!");
    }

    private void teleportToClanHome(Player p) {
        Clan c = clans.get(playerClan.get(p.getUniqueId()));
        if (c == null) return;
        if (c.home == null) { p.sendMessage("§cДом не установлен!"); return; }
        if (homeTeleportDelay > 0) {
            p.sendMessage("§eТелепортация через " + homeTeleportDelay + " сек. Не двигайтесь!");
            Location home = c.home.clone();
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (p.isOnline()) {
                        p.teleport(home);
                        p.sendMessage("§aТелепортирован!");
                    }
                }
            }.runTaskLater(this, homeTeleportDelay * 20L);
        } else {
            p.teleport(c.home);
            p.sendMessage("§aТелепортирован!");
        }
    }

    private void acceptInvite(Player p) {
        UUID u = p.getUniqueId();
        if (!invites.containsKey(u)) return;
        String cn = invites.remove(u);
        Clan c = clans.get(cn);
        if (c == null || c.members.size() >= maxMembers) { p.sendMessage("§cКлан заполнен!"); return; }
        c.members.add(u);
        c.roles.put(u, "Участник");
        playerClan.put(u, cn);
        c.tokens += tokensForJoin;
        saveData();
        p.sendMessage("§aТы в клане '" + cn + "'!");
    }

    private void leaveClan(Player p) {
        UUID u = p.getUniqueId();
        String cn = playerClan.remove(u);
        if (cn == null) return;
        Clan c = clans.get(cn);
        if (c == null) return;
        c.members.remove(u);
        c.roles.remove(u);
        if (c.owner.equals(u) && !c.members.isEmpty()) { c.owner = c.members.get(0); c.roles.put(c.owner, "Глава"); }
        if (c.members.isEmpty()) clans.remove(cn);
        saveData();
        p.sendMessage("§cТы покинул клан");
    }

    private void disbandClan(Player p) {
        Clan c = clans.get(playerClan.get(p.getUniqueId()));
        if (c == null || !c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Глава")) { p.sendMessage("§cНет прав!"); return; }
        for (UUID m : new ArrayList<>(c.members)) playerClan.remove(m);
        clans.remove(c.name);
        saveData();
        p.sendMessage("§4Клан удалён");
    }

    private void kickMember(Player p, String t) {
        if (t == null) return;
        Clan c = clans.get(playerClan.get(p.getUniqueId()));
        if (c == null) return;
        if (!c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Глава") && !c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Модератор")) { p.sendMessage("§cНет прав!"); return; }
        Player target = Bukkit.getPlayer(t);
        UUID tu = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(t).getUniqueId();
        if (!c.members.contains(tu)) return;
        if (c.roles.getOrDefault(tu, "Участник").equals("Глава")) return;
        c.members.remove(tu);
        c.roles.remove(tu);
        playerClan.remove(tu);
        saveData();
        p.sendMessage("§a" + t + " исключён");
        if (target != null) target.sendMessage("§cТебя кикнули из клана");
    }

    private void setRole(Player p, String t, String r) {
        if (t == null || r == null) return;
        Clan c = clans.get(playerClan.get(p.getUniqueId()));
        if (c == null || !c.roles.getOrDefault(p.getUniqueId(), "Участник").equals("Глава")) { p.sendMessage("§cНет прав!"); return; }
        if (!List.of("Участник", "Модератор", "Глава").contains(r)) { p.sendMessage("§cРоль: Участник, Модератор, Глава"); return; }
        Player target = Bukkit.getPlayer(t);
        UUID tu = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(t).getUniqueId();
        if (!c.members.contains(tu)) return;
        c.roles.put(tu, r);
        if (r.equals("Глава")) c.owner = tu;
        saveData();
        p.sendMessage("§aРоль " + t + " → " + r);
    }

    private ItemStack createItem(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        i.setItemMeta(meta);
        return i;
    }

    private ItemStack createPlayerHead(String n, String name, String... lore) {
        ItemStack i = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) i.getItemMeta();
        m.setOwningPlayer(Bukkit.getOfflinePlayer(n));
        m.setDisplayName(name);
        if (lore.length > 0) m.setLore(Arrays.asList(lore));
        i.setItemMeta(m);
        return i;
    }

    enum QuestTrigger { BREAK, KILL, EAT, CRAFT, CRAFT_POTION, HAVE_MONEY }

    static class BaseQuest {
        String name; QuestTrigger trigger; Material material; EntityType entityType; PotionType potionType;
        int target, tokenReward, moneyRequired;
        Material getIconMaterial() {
            if (material != null) return material;
            if (entityType != null) {
                return switch (entityType) {
                    case CREEPER -> Material.CREEPER_HEAD;
                    case SKELETON -> Material.SKELETON_SKULL;
                    case COW -> Material.BEEF;
                    case ENDERMAN -> Material.ENDER_PEARL;
                    default -> Material.BOOK;
                };
            }
            if (potionType != null) return Material.POTION;
            return Material.BOOK;
        }
    }

    static class DailyQuest extends BaseQuest {
        DailyQuest(String n, QuestTrigger t, Object v, int target, int tok, int mon) {
            this.name = n; this.trigger = t; this.target = target; this.tokenReward = tok; this.moneyRequired = mon;
            if (v instanceof Material) material = (Material) v;
            else if (v instanceof EntityType) entityType = (EntityType) v;
            else if (v instanceof PotionType) potionType = (PotionType) v;
        }
    }

    static class WeeklyQuest extends BaseQuest {
        WeeklyQuest(String n, QuestTrigger t, Object v, int target, int tok, int mon) {
            this.name = n; this.trigger = t; this.target = target; this.tokenReward = tok; this.moneyRequired = mon;
            if (v instanceof Material) material = (Material) v;
            else if (v instanceof EntityType) entityType = (EntityType) v;
            else if (v instanceof PotionType) potionType = (PotionType) v;
        }
    }

    static class QuestProgress { int progress = 0; }

    static class Clan {
        String name; UUID owner; String description; List<UUID> members; Map<UUID, String> roles;
        int tokens, maxMembers; Location home;
        Clan(String n, UUID o) {
            name = n; owner = o; description = "Без описания";
            members = new ArrayList<>(List.of(o)); roles = new HashMap<>();
            tokens = 0; maxMembers = 10; home = null;
        }
    }
}