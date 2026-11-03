package zypchat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZYPCHAT - Gelismis Turkce sohbet koruma eklentisi
 * Paper 1.21+ | Tek dosya surumu
 */
public final class ZYPCHAT extends JavaPlugin {

    private static ZYPCHAT instance;
    private ConfigManager configManager;
    private WarningManager warningManager;
    private PunishmentManager punishmentManager;
    private DiscordWebhook discordWebhook;
    private ChatListener chatListener;
    private AdminGUI adminGUI;

    @Override
    public void onEnable() {
        instance = this;
        ensureConfigFiles();
        this.configManager = new ConfigManager(this);
        this.warningManager = new WarningManager(this);
        this.punishmentManager = new PunishmentManager(this);
        this.discordWebhook = new DiscordWebhook(this);
        this.chatListener = new ChatListener(this);
        this.adminGUI = new AdminGUI(this);

        getServer().getPluginManager().registerEvents(chatListener, this);
        getServer().getPluginManager().registerEvents(adminGUI, this);
        Objects.requireNonNull(getCommand("zypchat")).setExecutor(new ChatGuardCommand(this));
        Objects.requireNonNull(getCommand("zypchat")).setTabCompleter(new ChatGuardTabCompleter());

        Bukkit.getScheduler().runTaskTimer(this, () -> warningManager.saveAll(), 6000L, 6000L);
        getLogger().info("ZYPCHAT aktif! Surum 1.0.0 | Paper 1.21");
    }

    @Override
    public void onDisable() {
        if (warningManager != null) warningManager.saveAll();
        getLogger().info("ZYPCHAT devre disi birakildi.");
    }

    private void ensureConfigFiles() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            getConfig().set("sohbet.kilitli", false);
            getConfig().set("ceza.max-uyari", 3);
            getConfig().set("ceza.susturma-suresi-dakika", 5);
            getConfig().set("ceza.ban-suresi", "1d");
            getConfig().set("spam.mesaj-gecmisi", 5);
            getConfig().set("spam.ayni-mesaj-limiti", 3);
            getConfig().set("spam.caps-orani", 0.7);
            getConfig().set("spam.max-uzunluk", 200);
            getConfig().set("log.konsol", true);
            getConfig().set("discord.aktif", false);
            getConfig().set("discord.webhook-url", "");
            getConfig().set("reklam.izinli-domainler",
                    List.of("sunucun.com", "youtube.com", "twitch.tv"));
            getConfig().set("mesajlar.engellendi", "&c[ZYPCHAT] &fMesajin engellendi: &e{sebep}");
            getConfig().set("mesajlar.uyari", "&e[ZYPCHAT] &fDikkat! Bu davranisin tekrari cezalandirilir.");
            getConfig().set("mesajlar.kilitli", "&c[ZYPCHAT] &fSohbet su an kilitli!");
            saveConfig();
        }

        File wordsFile = new File(getDataFolder(), "words.yml");
        if (!wordsFile.exists()) {
            YamlConfiguration yaml = new YamlConfiguration();
            String[] defaults = {"amk","aq","orospu","sik","yarrak","pic","pezevenk",
                    "gavat","siktir","oc","amcik","sikik","ibne","yavsak"};
            for (String w : defaults) yaml.set("kelimeler." + w, "BLOCK");
            yaml.set("kelimeler.aptal", "WARN");
            yaml.set("kelimeler.salak", "WARN");
            yaml.set("kelimeler.gerizekali", "WARN");
            try { yaml.save(wordsFile); }
            catch (IOException e) { getLogger().severe("words.yml olusturulamadi: " + e.getMessage()); }
        }
    }

    public static ZYPCHAT getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public WarningManager getWarningManager() { return warningManager; }
    public PunishmentManager getPunishmentManager() { return punishmentManager; }
    public DiscordWebhook getDiscordWebhook() { return discordWebhook; }
    public ChatListener getChatListener() { return chatListener; }
    public AdminGUI getAdminGUI() { return adminGUI; }
}

class ConfigManager {
    private final ZYPCHAT plugin;
    private final Map<String, String> bannedWords = new LinkedHashMap<>();
    private final List<Pattern> adPatterns = new ArrayList<>();
    private final Set<String> whitelistedDomains = new HashSet<>();

    ConfigManager(ZYPCHAT plugin) { this.plugin = plugin; reload(); }

    void reload() {
        plugin.reloadConfig();
        bannedWords.clear(); adPatterns.clear(); whitelistedDomains.clear();

        File wordsFile = new File(plugin.getDataFolder(), "words.yml");
        YamlConfiguration words = YamlConfiguration.loadConfiguration(wordsFile);
        if (words.getConfigurationSection("kelimeler") != null) {
            for (String key : words.getConfigurationSection("kelimeler").getKeys(false)) {
                bannedWords.put(key.toLowerCase(Locale.ROOT),
                        words.getString("kelimeler." + key, "BLOCK"));
            }
        }
        adPatterns.add(Pattern.compile("(?i)(https?://|www\\.)\\S+"));
        adPatterns.add(Pattern.compile("(?i)\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"));
        adPatterns.add(Pattern.compile("(?i)discord\\.(gg|com)/\\S+"));
        adPatterns.add(Pattern.compile("(?i)\\.(com|net|org|gg|tk|xyz|shop|site)\\b"));

        FileConfiguration c = plugin.getConfig();
        whitelistedDomains.addAll(c.getStringList("reklam.izinli-domainler"));
    }

    boolean isChatLocked() { return plugin.getConfig().getBoolean("sohbet.kilitli", false); }
    void setChatLocked(boolean v) {
        plugin.getConfig().set("sohbet.kilitli", v); plugin.saveConfig();
    }
    int getMaxWarnings() { return plugin.getConfig().getInt("ceza.max-uyari", 3); }
    int getMuteDuration() { return plugin.getConfig().getInt("ceza.susturma-suresi-dakika", 5); }
    String getBanDuration() { return plugin.getConfig().getString("ceza.ban-suresi", "1d"); }
    boolean isLogToConsole() { return plugin.getConfig().getBoolean("log.konsol", true); }
    boolean isDiscordEnabled() { return plugin.getConfig().getBoolean("discord.aktif", false); }
    String getDiscordWebhookUrl() { return plugin.getConfig().getString("discord.webhook-url", ""); }
    int getSpamHistory() { return plugin.getConfig().getInt("spam.mesaj-gecmisi", 5); }
    int getSameMessageLimit() { return plugin.getConfig().getInt("spam.ayni-mesaj-limiti", 3); }
    double getCapsRatio() { return plugin.getConfig().getDouble("spam.caps-orani", 0.7); }
    int getMaxLength() { return plugin.getConfig().getInt("spam.max-uzunluk", 200); }
    Map<String, String> getBannedWords() { return Collections.unmodifiableMap(bannedWords); }
    List<Pattern> getAdPatterns() { return Collections.unmodifiableList(adPatterns); }
    Set<String> getWhitelistedDomains() { return Collections.unmodifiableSet(whitelistedDomains); }
    String msg(String key, String def) {
        return plugin.getConfig().getString("mesajlar." + key, def).replace("&", "\u00a7");
    }
}

class ChatListener implements Listener {
    private final ZYPCHAT plugin;
    private final Map<UUID, Deque<MessageInfo>> history = new ConcurrentHashMap<>();

    ChatListener(ZYPCHAT plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("zypchat.bypass")) return;

        if (plugin.getConfigManager().isChatLocked() && !player.hasPermission("zypchat.admin")) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(plugin.getConfigManager().msg("kilitli",
                            "&c[ZYPCHAT] &fSohbet kilitli!")));
            return;
        }

        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        FilterResult result = check(player, raw);
        if (!result.isBlocked()) return;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            String reason = result.getReason();
            player.sendMessage(plugin.getConfigManager()
                    .msg("engellendi", "&c[ZYPCHAT] &fMesajin engellendi: &e{sebep}")
                    .replace("{sebep}", reason));
            if (result.isWarningOnly()) {
                player.sendMessage(plugin.getConfigManager().msg("uyari",
                        "&e[ZYPCHAT] &fDikkat!"));
            }
            plugin.getWarningManager().addWarning(player.getUniqueId(), reason);
            plugin.getPunishmentManager().apply(player, result);
            if (plugin.getConfigManager().isLogToConsole()) {
                plugin.getLogger().warning("[ENGELLENDI] " + player.getName() + " -> "
                        + raw + " | Sebep: " + reason);
            }
            if (plugin.getConfigManager().isDiscordEnabled()) {
                plugin.getDiscordWebhook().send(player.getName(), raw, reason);
            }
        });
    }

    private FilterResult check(Player player, String message) {
        if (message.length() > plugin.getConfigManager().getMaxLength())
            return FilterResult.blocked("Mesaj cok uzun", "LENGTH");
        if (message.trim().isEmpty()) return FilterResult.blocked("Bos mesaj", "EMPTY");

        String normalized = normalize(message);

        for (Map.Entry<String, String> e : plugin.getConfigManager().getBannedWords().entrySet()) {
            final String word = e.getKey();
            if (normalized.contains(word)) {
                return switch (e.getValue().toUpperCase(Locale.ROOT)) {
                    case "WARN" -> FilterResult.warn("Uygunsuz ifade", word);
                    case "REPLACE" -> FilterResult.blocked("Uygunsuz ifade", word);
                    default -> FilterResult.blocked("Yasakli kelime", word);
                };
            }
        }

        for (Pattern p : plugin.getConfigManager().getAdPatterns()) {
            Matcher m = p.matcher(message);
            if (m.find()) {
                String found = m.group();
                boolean ok = plugin.getConfigManager().getWhitelistedDomains().stream()
                        .anyMatch(found.toLowerCase(Locale.ROOT)::contains);
                if (!ok) return FilterResult.blocked("Reklam/URL yasak", found);
            }
        }

        String trimmed = message.trim();
        if (trimmed.length() > 5) {
            long letters = trimmed.chars().filter(Character::isLetter).count();
            long uppers = trimmed.chars().filter(Character::isUpperCase).count();
            if (letters > 0 && (double) uppers / letters >= plugin.getConfigManager().getCapsRatio())
                return FilterResult.blocked("Buyuk harf kullanimi", "CAPS");
        }

        if (message.matches(".*(.)\\1{7,}.*")) return FilterResult.blocked("Karakter spam", "CHARSPAM");

        Deque<MessageInfo> deque = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        while (!deque.isEmpty() && now - deque.peekFirst().timestamp > 10_000) deque.pollFirst();
        long sameCount = deque.stream().filter(mi -> mi.message.equalsIgnoreCase(message)).count();
        if (sameCount >= plugin.getConfigManager().getSameMessageLimit())
            return FilterResult.blocked("Ayni mesaj spam", "SAME");
        if (deque.size() >= plugin.getConfigManager().getSpamHistory())
            return FilterResult.blocked("Mesaj spam", "FLOOD");
        deque.addLast(new MessageInfo(message, now));
        return FilterResult.allowed();
    }

    private String normalize(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        s = s.replace("@", "a").replace("4", "a").replace("3", "e")
                .replace("1", "i").replace("!", "i").replace("0", "o")
                .replace("5", "s").replace("$", "s").replace("+", "t")
                .replace("7", "t").replace("8", "b").replace("*", "")
                .replace(".", "").replace(",", "").replace("-", "")
                .replace("_", "").replace(" ", "");
        s = s.replaceAll("(.)\\1{2,}", "$1");
        return s;
    }
}

class PunishmentManager {
    private final ZYPCHAT plugin;
    PunishmentManager(ZYPCHAT plugin) { this.plugin = plugin; }

    void apply(Player player, FilterResult result) {
        if (result.isWarningOnly()) return;
        int w = plugin.getWarningManager().getWarnings(player.getUniqueId());
        int max = plugin.getConfigManager().getMaxWarnings();

        if (w >= max * 2) {
            String cmd = "ban " + player.getName() + " "
                    + plugin.getConfigManager().getBanDuration() + " ZYPCHAT: Kural ihlali";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            plugin.getLogger().warning(player.getName() + " banlandi (ZYPCHAT).");
        } else if (w >= max) {
            int mins = plugin.getConfigManager().getMuteDuration();
            String cmd = "mute " + player.getName() + " " + mins + "m ZYPCHAT: Kural ihlali";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            player.sendMessage("\u00a7c[ZYPCHAT] \u00a7f" + mins + " dakika susturuldun.");
        } else if (w >= max / 2) {
            player.sendMessage("\u00a7e[ZYPCHAT] \u00a7fUyari " + w + "/" + max + " - dikkatli ol!");
        }
    }
}

class WarningManager {
    private final ZYPCHAT plugin;
    private final Map<UUID, List<WarningEntry>> warnings = new ConcurrentHashMap<>();
    private final File file;

    WarningManager(ZYPCHAT plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "warnings.yml");
        load();
    }

    void addWarning(UUID uuid, String reason) {
        warnings.computeIfAbsent(uuid, k -> new ArrayList<>())
                .add(new WarningEntry(System.currentTimeMillis(), reason));
    }
    int getWarnings(UUID uuid) { return warnings.getOrDefault(uuid, Collections.emptyList()).size(); }
    void clear(UUID uuid) { warnings.remove(uuid); }

    Map<UUID, Integer> getTopWarningers(int limit) {
        return warnings.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(limit)
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue().size()),
                        LinkedHashMap::putAll);
    }

    void saveAll() {
        YamlConfiguration yaml = new YamlConfiguration();
        warnings.forEach((uuid, list) -> {
            List<String> s = new ArrayList<>();
            for (WarningEntry we : list) s.add(we.timestamp + "|" + we.reason);
            yaml.set(uuid.toString(), s);
        });
        try { yaml.save(file); }
        catch (IOException e) { plugin.getLogger().severe("warnings.yml kaydedilemedi: " + e.getMessage()); }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                List<String> raw = yaml.getStringList(key);
                List<WarningEntry> list = new ArrayList<>();
                for (String s : raw) {
                    String[] p = s.split("\\|", 2);
                    if (p.length == 2) list.add(new WarningEntry(Long.parseLong(p[0]), p[1]));
                }
                warnings.put(uuid, list);
            } catch (Exception ignored) {}
        }
    }
}

class DiscordWebhook {
    private final ZYPCHAT plugin;
    DiscordWebhook(ZYPCHAT plugin) { this.plugin = plugin; }

    void send(String player, String message, String reason) {
        String url = plugin.getConfigManager().getDiscordWebhookUrl();
        if (url == null || url.isBlank()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "ZYPCHAT/1.0");
                conn.setDoOutput(true);
                String safeMsg = message.replace("\"", "'").replace("\n", " ");
                String json = String.format(
                        "{\"content\":\"**ZYPCHAT** | `%s` | Mesaj: `%s` | Sebep: `%s`\"}",
                        player, safeMsg, reason);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Discord webhook hatasi: " + e.getMessage());
            }
        });
    }
}

class AdminGUI implements Listener {
    private final ZYPCHAT plugin;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    AdminGUI(ZYPCHAT plugin) { this.plugin = plugin; }

    void open(Player admin) {
        Inventory inv = Bukkit.createInventory(null, 54,
                LEGACY.deserialize("\u00a78ZYPCHAT - Uyari Siralamasi"));

        Map<UUID, Integer> top = plugin.getWarningManager().getTopWarningers(45);
        int slot = 0;
        for (Map.Entry<UUID, Integer> e : top.entrySet()) {
            if (slot >= 45) break;
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (name == null) name = "Bilinmeyen";

            int wc = e.getValue();
            Material mat = wc >= 5 ? Material.RED_WOOL
                    : wc >= 2 ? Material.ORANGE_WOOL : Material.YELLOW_WOOL;
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(LEGACY.deserialize("\u00a7e" + name));
                meta.lore(List.of(
                        LEGACY.deserialize("\u00a77Uyari: \u00a7c" + wc),
                        LEGACY.deserialize("\u00a77UUID: \u00a78"
                                + e.getKey().toString().substring(0, 8) + "..."),
                        LEGACY.deserialize(""),
                        LEGACY.deserialize("\u00a7aSol tik: \u00a7fUyarilari sifirla")));
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.displayName(LEGACY.deserialize("\u00a76ZYPCHAT Istatistikleri"));
            im.lore(List.of(
                    LEGACY.deserialize("\u00a77Toplam uyarili oyuncu: \u00a7e" + top.size()),
                    LEGACY.deserialize("\u00a77Sohbet durumu: \u00a7e"
                            + (plugin.getConfigManager().isChatLocked()
                                    ? "\u00a7cKILITLI" : "\u00a7aACIK")),
                    LEGACY.deserialize("\u00a77Surum: \u00a7a1.0.0 (Paper 1.21)")));
            info.setItemMeta(im);
        }
        inv.setItem(49, info);
        admin.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());
        if (!title.contains("ZYPCHAT")) return;
        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        if (clicked.getType() == Material.BOOK) return;

        ItemMeta m = clicked.getItemMeta();
        if (m == null || m.displayName() == null) return;
        String raw = PlainTextComponentSerializer.plainText().serialize(m.displayName());
        raw = raw.replace("\u00a7e", "").trim();

        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) {
            plugin.getWarningManager().clear(online.getUniqueId());
            p.sendMessage("\u00a7a" + raw + " oyuncusunun uyarilari sifirlandi.");
            p.closeInventory();
            return;
        }
        for (Map.Entry<UUID, Integer> e2 : plugin.getWarningManager().getTopWarningers(45).entrySet()) {
            String name = Bukkit.getOfflinePlayer(e2.getKey()).getName();
            if (raw.equals(name)) {
                plugin.getWarningManager().clear(e2.getKey());
                p.sendMessage("\u00a7a" + raw + " oyuncusunun uyarilari sifirlandi.");
                p.closeInventory();
                return;
            }
        }
    }
}

class ChatGuardCommand implements CommandExecutor {
    private final ZYPCHAT plugin;
    ChatGuardCommand(ZYPCHAT plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("zypchat.admin")) {
            sender.sendMessage("\u00a7cYetkiniz yok.");
            return true;
        }
        if (args.length == 0) { help(sender, label); return true; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.getConfigManager().reload();
                sender.sendMessage("\u00a7aZYPCHAT ayarlari yenilendi.");
            }
            case "clear" -> {
                for (int i = 0; i < 100; i++) Bukkit.getServer().broadcast(Component.empty());
                sender.sendMessage("\u00a7aSohbet temizlendi.");
            }
            case "lock" -> {
                plugin.getConfigManager().setChatLocked(true);
                Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection()
                        .deserialize("\u00a7c[ZYPCHAT] \u00a7fSohbet kilitlendi."));
            }
            case "unlock" -> {
                plugin.getConfigManager().setChatLocked(false);
                Bukkit.getServer().broadcast(LegacyComponentSerializer.legacySection()
                        .deserialize("\u00a7a[ZYPCHAT] \u00a7fSohbet acildi."));
            }
            case "gui" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("\u00a7cSadece oyuncular."); return true; }
                plugin.getAdminGUI().open(p);
            }
            case "reset" -> {
                if (args.length < 2) { sender.sendMessage("\u00a7cKullanim: /" + label + " reset <oyuncu>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("\u00a7cOyuncu bulunamadi."); return true; }
                plugin.getWarningManager().clear(target.getUniqueId());
                sender.sendMessage("\u00a7a" + args[1] + " uyarilari sifirlandi.");
            }
            case "stats" -> {
                if (!(sender instanceof Player p)) return true;
                int w = plugin.getWarningManager().getWarnings(p.getUniqueId());
                p.sendMessage("\u00a7e[ZYPCHAT] \u00a7fUyarilariniz: \u00a7c" + w);
            }
            case "test" -> {
                if (args.length < 2) { sender.sendMessage("\u00a7cKullanim: /" + label + " test <mesaj>"); return true; }
                String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                sender.sendMessage("\u00a7eTest: \u00a77" + msg);
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        s.sendMessage("\u00a78\u00a7m----------\u00a7r \u00a76ZYPCHAT \u00a78\u00a7m----------");
        s.sendMessage("\u00a7e/" + label + " reload \u00a77- Ayarlari yeniler");
        s.sendMessage("\u00a7e/" + label + " clear \u00a77- Sohbeti temizler");
        s.sendMessage("\u00a7e/" + label + " lock \u00a77- Sohbeti kilitler");
        s.sendMessage("\u00a7e/" + label + " unlock \u00a77- Sohbeti acar");
        s.sendMessage("\u00a7e/" + label + " gui \u00a77- Uyari panelini acar");
        s.sendMessage("\u00a7e/" + label + " reset <oyuncu> \u00a77- Uyarilari sifirlar");
        s.sendMessage("\u00a7e/" + label + " stats \u00a77- Kendi uyarilarinizi gosterir");
        s.sendMessage("\u00a78\u00a7m--------------------------------");
    }
}

class ChatGuardTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (!s.hasPermission("zypchat.admin")) return List.of();
        if (args.length == 1) {
            return List.of("reload", "clear", "lock", "unlock", "gui", "reset", "stats", "test")
                    .stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}

class FilterResult {
    private final boolean blocked;
    private final boolean warningOnly;
    private final String reason;
    private final String matched;

    private FilterResult(boolean b, boolean w, String r, String m) {
        blocked = b; warningOnly = w; reason = r; matched = m;
    }
    static FilterResult blocked(String r, String m) { return new FilterResult(true, false, r, m); }
    static FilterResult warn(String r, String m) { return new FilterResult(true, true, r, m); }
    static FilterResult allowed() { return new FilterResult(false, false, "", ""); }
    boolean isBlocked() { return blocked; }
    boolean isWarningOnly() { return warningOnly; }
    String getReason() { return reason; }
    String getMatched() { return matched; }
}

class WarningEntry {
    final long timestamp;
    final String reason;
    WarningEntry(long t, String r) { timestamp = t; reason = r; }
}

class MessageInfo {
    final String message;
    final long timestamp;
    MessageInfo(String m, long t) { message = m; timestamp = t; }
}
