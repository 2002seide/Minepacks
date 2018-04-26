/*
 *   Copyright (C) 2016-2018 GeorgH93
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package at.pcgamingfreaks.Minepacks.Bukkit;

import at.pcgamingfreaks.Bukkit.MCVersion;
import at.pcgamingfreaks.Bukkit.Message.Message;
import at.pcgamingfreaks.Bukkit.Updater;
import at.pcgamingfreaks.Bukkit.Utils;
import at.pcgamingfreaks.ConsoleColor;
import at.pcgamingfreaks.Minepacks.Bukkit.API.Backpack;
import at.pcgamingfreaks.Minepacks.Bukkit.API.Callback;
import at.pcgamingfreaks.Minepacks.Bukkit.API.MinepacksPlugin;
import at.pcgamingfreaks.Minepacks.Bukkit.Commands.OnCommand;
import at.pcgamingfreaks.Minepacks.Bukkit.Database.Config;
import at.pcgamingfreaks.Minepacks.Bukkit.Database.Helper.WorldBlacklistMode;
import at.pcgamingfreaks.Minepacks.Bukkit.Database.Language;
import at.pcgamingfreaks.Minepacks.Bukkit.Listener.DisableShulkerboxes;
import at.pcgamingfreaks.Minepacks.Bukkit.Database.Database;
import at.pcgamingfreaks.Minepacks.Bukkit.Listener.DropOnDeath;
import at.pcgamingfreaks.Minepacks.Bukkit.Listener.EventListener;
import at.pcgamingfreaks.Minepacks.Bukkit.Listener.ItemFilter;
import at.pcgamingfreaks.StringUtils;

import org.apache.commons.lang3.Validate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Minepacks extends JavaPlugin implements MinepacksPlugin
{
	private static Minepacks instance = null;

	public Config config;
	public Language lang;
	private Database database;

	public final Map<UUID, Long> cooldowns = new HashMap<>();

	public String backpackTitleOther = "%s Backpack", backpackTitle = "Backpack";
	public Message messageNoPermission, messageInvalidBackpack, messageWorldDisabled;

	private int maxSize;
	private Collection<String> worldBlacklist;
	private WorldBlacklistMode worldBlacklistMode;

	public static Minepacks getInstance()
	{
		return instance;
	}

	@Override
	public void onEnable()
	{
		Utils.warnOnJava_1_7(getLogger());
		//region Check compatibility with used minecraft version
		if(MCVersion.is(MCVersion.UNKNOWN) || MCVersion.isNewerThan(MCVersion.MC_NMS_1_12_R1))
		{
			String name = Bukkit.getServer().getClass().getPackage().getName();
			String[] version = name.substring(name.lastIndexOf('.') + 2).split("_");
			this.warnOnVersionIncompatibility(version[0] + "." + version[1]);
			this.setEnabled(false);
			return;
		}
		//endregion
		//region check if a plugin folder exists (was renamed from MinePacks to Minepacks with the V2.0 update)
		if(!getDataFolder().exists())
		{
			File oldPluginFolder = new File(getDataFolder().getParentFile(), "MinePacks");
			if(oldPluginFolder.exists() && !oldPluginFolder.renameTo(getDataFolder()))
			{
				getLogger().warning("Failed to rename the plugins data-folder.\n" +
						            "Please rename the \"MinePacks\" folder to \"Minepacks\" and restart the server, to move your data from Minepacks V1.X to Minepacks V2.X!");
			}
		}
		//endregion
		instance = this;
		config = new Config(this);
		lang = new Language(this);

		load();

		if(config.getAutoUpdate()) // Lets check for updates
		{
			getLogger().info("Checking for updates ...");
			Updater updater = new Updater(this, this.getFile(), true, 83445); // Create a new updater with dev.bukkit.org as update provider
			updater.update(); // Starts the update
		}
		StringUtils.getPluginEnabledMessage(getDescription().getName());
	}

	@Override
	public void onDisable()
	{
		Updater updater = null;
		if(config.getAutoUpdate()) // Lets check for updates
		{
			getLogger().info("Checking for updates ...");
			updater = new Updater(this, this.getFile(), true, 83445); // Create a new updater with dev.bukkit.org as update provider
			updater.update(); // Starts the update, if there is a new update available it will download while we close the rest
		}
		unload();
		if(updater != null) updater.waitForAsyncOperation(); // The update can download while we kill the listeners and close the DB connections
		StringUtils.getPluginDisabledMessage(getDescription().getName());
	}

	private void load()
	{
		lang.load(config.getLanguage(), config.getLanguageUpdateMode());
		database = Database.getDatabase(this);
		maxSize = config.getBackpackMaxSize();
		backpackTitleOther = config.getBPTitleOther();
		backpackTitle = StringUtils.limitLength(config.getBPTitle(), 32);
		messageNoPermission = lang.getMessage("Ingame.NoPermission");
		messageInvalidBackpack = lang.getMessage("Ingame.InvalidBackpack");
		messageWorldDisabled   = lang.getMessage("Ingame.WorldDisabled");

		getCommand("backpack").setExecutor(new OnCommand(this));
		//region register events
		PluginManager pluginManager = getServer().getPluginManager();
		pluginManager.registerEvents(new EventListener(this), this);
		if(config.getDropOnDeath()) pluginManager.registerEvents(new DropOnDeath(this), this);
		if(config.isItemFilterEnabled()) pluginManager.registerEvents(new ItemFilter(this), this);
		if(MCVersion.isNewerOrEqualThan(MCVersion.MC_1_11) && config.isShulkerboxesDisable()) pluginManager.registerEvents(new DisableShulkerboxes(this), this);
		//endregion
		if(config.getFullInvCollect())
		{
			(new ItemsCollector(this)).runTaskTimer(this, config.getFullInvCheckInterval(), config.getFullInvCheckInterval());
		}
		worldBlacklist = config.getWorldBlacklist();
		if(worldBlacklist.size() == 0)
		{
			worldBlacklistMode = WorldBlacklistMode.None;
		}
		else
		{
			worldBlacklistMode = config.getWorldBlacklistMode();
		}
	}

	private void unload()
	{
		getServer().getScheduler().cancelTasks(this); // Stop the listener, we don't need them any longer
		database.close(); // Close the DB connection, we won't need them any longer
		instance = null;
	}

	public void reload()
	{
		unload();
		config.reload();
		load();
	}

	public void warnOnVersionIncompatibility(String version)
	{
		getLogger().warning(ConsoleColor.RED + "################################" + ConsoleColor.RESET);
		getLogger().warning(ConsoleColor.RED + String.format("Your minecraft version (MC %1$s) is currently not compatible with this plugins version (%2$s). " +
				                                                     "Please check for updates!", version, getDescription().getVersion()) + ConsoleColor.RESET);
		getLogger().warning(ConsoleColor.RED + "################################" + ConsoleColor.RESET);
		Utils.blockThread(5);
	}

	public Config getConfiguration()
	{
		return config;
	}

	public Database getDb()
	{
		return database;
	}

	@Override
	public void openBackpack(@NotNull final Player opener, @NotNull final OfflinePlayer owner, final boolean editable)
	{
		Validate.notNull(owner);
		database.getBackpack(owner, new Callback<at.pcgamingfreaks.Minepacks.Bukkit.Backpack>()
		{
			@Override
			public void onResult(at.pcgamingfreaks.Minepacks.Bukkit.Backpack backpack)
			{
				openBackpack(opener, backpack, editable);
			}

			@Override
			public void onFail() {}
		});
	}

	@Override
	public void openBackpack(@NotNull final Player opener, @Nullable final Backpack backpack, boolean editable)
	{
		Validate.notNull(opener);
		WorldBlacklistMode disabled = isDisabled(opener);
		if(disabled != WorldBlacklistMode.None)
		{
			switch(disabled)
			{
				case Message: messageWorldDisabled.send(opener); break;
				case MissingPermission: messageNoPermission.send(opener); break;
			}
			return;
		}
		if(backpack == null)
		{
			messageInvalidBackpack.send(opener);
			return;
		}
		backpack.open(opener, editable);
	}

	@Override
	public @Nullable Backpack getBackpackCachedOnly(@NotNull OfflinePlayer owner)
	{
		return database.getBackpack(owner);
	}

	@Override
	public void getBackpack(@NotNull OfflinePlayer owner, @NotNull Callback<at.pcgamingfreaks.Minepacks.Bukkit.Backpack> callback)
	{
		database.getBackpack(owner, callback);
	}

	public int getBackpackPermSize(Player player)
	{
		for(int i = maxSize; i > 1; i--)
		{
			if(player.hasPermission("backpack.size." + i))
			{
				return i * 9;
			}
		}
		return 9;
	}

	public WorldBlacklistMode isDisabled(Player player)
	{
		if(worldBlacklistMode == WorldBlacklistMode.None || (worldBlacklistMode != WorldBlacklistMode.NoPlugin && player.hasPermission("backpack.ignoreWorldBlacklist"))) return WorldBlacklistMode.None;
		if(worldBlacklist.contains(player.getWorld().getName().toLowerCase()))
		{
			return worldBlacklistMode;
		}
		return WorldBlacklistMode.None;
	}
}