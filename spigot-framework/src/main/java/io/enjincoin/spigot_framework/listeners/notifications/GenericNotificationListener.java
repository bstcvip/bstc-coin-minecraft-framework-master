package io.enjincoin.spigot_framework.listeners.notifications;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.enjincoin.sdk.client.enums.NotificationType;
import io.enjincoin.sdk.client.service.identities.vo.Identity;
import io.enjincoin.sdk.client.service.identities.vo.IdentityField;
import io.enjincoin.sdk.client.service.identity.vo.TokenEntry;
import io.enjincoin.sdk.client.service.notifications.NotificationListener;
import io.enjincoin.sdk.client.service.tokens.vo.Token;
import io.enjincoin.sdk.client.vo.notifications.NotificationEvent;
import io.enjincoin.spigot_framework.BasePlugin;
import io.enjincoin.spigot_framework.inventory.WalletInventory;
import io.enjincoin.spigot_framework.util.UuidUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class GenericNotificationListener implements NotificationListener {

    private BasePlugin main;

    public GenericNotificationListener(BasePlugin main) {
        this.main = main;
    }

    @Override
    public void notificationReceived(NotificationEvent event) {
        this.main.getBootstrap().debug(String.format("Received %s event with data: %s", event.getNotificationType().getEventType(), event.getSourceData()));
        if (event.getNotificationType() == NotificationType.TX_EXECUTED) {
            this.main.getBootstrap().debug(String.format("Parsing data for %s event", event.getNotificationType().getEventType()));
            JsonParser parser = new JsonParser();
            JsonObject data = parser.parse(event.getSourceData()).getAsJsonObject()
                    .get("data").getAsJsonObject();
            if (data.get("event").getAsString().equalsIgnoreCase("melt")) {
                String ethereumAddress = data.get("param1").getAsString();
                double amount = Double.valueOf(data.get("param2").getAsString());
                int tokenId = data.get("token").getAsJsonObject().get("token_id").getAsInt();
                int appId = data.get("token").getAsJsonObject().get("app_id").getAsInt();

                this.main.getBootstrap().debug(String.format("%s of token %s was melted by %s", amount, tokenId, ethereumAddress));

                JsonObject config = this.main.getBootstrap().getConfig();
                if (config.get("appId").getAsInt() == appId) {
                    this.main.getBootstrap().debug(String.format("Updating balance of player linked to %s", ethereumAddress));
                    Identity identity = getIdentity(ethereumAddress);

                    if (identity != null)
                        addTokenValue(identity, tokenId, -amount);
                }
            } else if (data.get("event").getAsString().equalsIgnoreCase("transfer")) {
                String fromEthereumAddress = data.get("param1").getAsString();
                String toEthereumAddress = data.get("param2").getAsString();
                double amount = Double.valueOf(data.get("param3").getAsString());
                int tokenId = data.get("token").getAsJsonObject().get("token_id").getAsInt();
                int appId = data.get("token").getAsJsonObject().get("app_id").getAsInt();

                this.main.getBootstrap().debug(String.format("%s received %s of %s tokens from %s", toEthereumAddress, amount, tokenId, fromEthereumAddress));

                JsonObject config = this.main.getBootstrap().getConfig();
                if (config.get("appId").getAsInt() == appId) {
                    this.main.getBootstrap().debug(String.format("Updating balance of player linked to %s", toEthereumAddress));
                    Identity toIdentity = getIdentity(toEthereumAddress);
                    Identity fromIdentity = getIdentity(fromEthereumAddress);

                    if (toIdentity != null)
                        addTokenValue(toIdentity, tokenId, amount);
                    if (fromIdentity != null)
                        addTokenValue(fromIdentity, tokenId, -amount);
                }
            }
        }
    }

    public Identity getIdentity(String address) {
        return this.main.getBootstrap().getIdentities().values().stream()
                .filter(i -> i != null && i.getEthereumAddress().equalsIgnoreCase(address))
                .findFirst()
                .orElse(null);
    }

    public TokenEntry getTokenEntry(Identity identity, int tokenId) {
        TokenEntry entry = null;
        for (TokenEntry e : identity.getTokens()) {
            if (e.getTokenId() == tokenId) {
                entry = e;
                break;
            }
        }
        return entry;
    }

    public void addTokenValue(Identity identity, int tokenId, double amount) {
        TokenEntry entry = getTokenEntry(identity, tokenId);
        if (entry != null)
            entry.setValue(entry.getValue() + amount);
        else {
            List<TokenEntry> entries = new ArrayList<>(Arrays.asList(identity.getTokens()));
            entries.add(new TokenEntry(tokenId, amount));
            identity.setTokens(entries.toArray(new TokenEntry[]{}));
        }

        updateInventory(identity, tokenId, amount);
    }

    public void updateInventory(Identity identity, int tokenId, double amount) {
        JsonObject config = main.getBootstrap().getConfig();

        String displayName = null;
        if (config.has("tokens")) {
            JsonObject tokens = config.getAsJsonObject("tokens");
            if (tokens.has(String.valueOf(tokenId))) {
                JsonObject token = tokens.getAsJsonObject(String.valueOf(tokenId));
                if (token.has("displayName")) {
                    displayName = token.get("displayName").getAsString();
                } else {
                    Token spec = main.getBootstrap().getTokens().get(tokenId);
                    if (spec != null) {
                        if (spec.getName() != null) {
                            displayName = spec.getName();
                        } else {
                            displayName = "Token #" + tokenId;
                        }
                    }
                }
            }
        }

        if (displayName != null) {
            UUID uuid = null;
            for (IdentityField field : identity.getFields()) {
                if (field.getKey().equalsIgnoreCase("uuid")) {
                    uuid = UuidUtil.stringToUuid(field.getFieldValue());
                    break;
                }
            }

            Player player = null;
            if (uuid != null) {
                player = Bukkit.getPlayer(uuid);
            }

            if (player != null) {
                InventoryView view = player.getOpenInventory();
                if (view != null && ChatColor.stripColor(view.getTitle()).equalsIgnoreCase("Enjin Wallet")) {
                    ItemStack stack = null;
                    ItemMeta meta = null;
                    int i;
                    for (i = 0; i < 6 * 9; i++) {
                        stack = view.getItem(i++);
                        if (stack != null) {
                            meta = stack.getItemMeta();
                            if (ChatColor.stripColor(meta.getDisplayName()).equalsIgnoreCase(displayName))
                                break;
                        }
                        stack = null;
                        meta = null;
                    }

                    if (stack == null) {
                        if (config.has("tokens")) {
                            JsonObject tokens = config.getAsJsonObject("tokens");
                            if (tokens.has(String.valueOf(tokenId))) {
                                JsonObject tokenDisplay = config.getAsJsonObject(String.valueOf(tokenId));
                                Token token = main.getBootstrap().getTokens().get(tokenId);
                                if (token != null) {
                                    Material material = null;
                                    if (tokenDisplay.has("material"))
                                        material = Material.getMaterial(tokenDisplay.get("material").getAsString());
                                    if (material == null)
                                        material = Material.APPLE;

                                    stack = new ItemStack(material);
                                    meta = stack.getItemMeta();

                                    if (tokenDisplay.has("displayName")) {
                                        meta.setDisplayName(ChatColor.DARK_PURPLE + tokenDisplay.get("displayName").getAsString());
                                    } else {
                                        if (token.getName() != null)
                                            meta.setDisplayName(ChatColor.DARK_PURPLE + token.getName());
                                        else
                                            meta.setDisplayName(ChatColor.DARK_PURPLE + "Token #" + token.getTokenId());
                                    }

                                    List<String> lore = new ArrayList<>();
                                    if (token.getDecimals() == 0) {
                                        int balance = Double.valueOf(amount).intValue();
                                        lore.add(ChatColor.GRAY + "Balance: " + ChatColor.GOLD + balance);
                                    } else {
                                        lore.add(ChatColor.GRAY + "Balance: " + ChatColor.GOLD + WalletInventory.DECIMAL_FORMAT.format(amount));
                                    }

                                    if (tokenDisplay.has("lore")) {
                                        JsonElement element = tokenDisplay.get("lore");
                                        if (element.isJsonArray()) {
                                            JsonArray array = element.getAsJsonArray();
                                            for (JsonElement line : array) {
                                                lore.add(ChatColor.DARK_GRAY + line.getAsString());
                                            }
                                        } else {
                                            lore.add(ChatColor.DARK_GRAY + element.getAsString());
                                        }
                                    }

                                    meta.setLore(lore);
                                    stack.setItemMeta(meta);
                                    view.setItem(i - 1, stack);
                                }
                            }
                        }
                    } else {
                        List<String> lore = meta.getLore();
                        String value = ChatColor.stripColor(lore.get(0)).replace("Balance: ", "");
                        if (value.contains(".")) {
                            Double val = Double.valueOf(value) + amount;
                            lore.set(0, ChatColor.GRAY + "Balance: " + ChatColor.GOLD + WalletInventory.DECIMAL_FORMAT.format(val));
                        } else {
                            Integer val = Double.valueOf(value).intValue() + Double.valueOf(amount).intValue();
                            lore.set(0, ChatColor.GRAY + "Balance: " + ChatColor.GOLD + val);
                        }
                        meta.setLore(lore);
                        stack.setItemMeta(meta);
                        view.setItem(i - 1, stack);
                    }

                    player.updateInventory();
                }
            }
        }
    }

}
