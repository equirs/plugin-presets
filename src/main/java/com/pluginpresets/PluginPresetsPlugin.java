/*
 * Copyright (c) 2022, antero111 <https://github.com/antero111>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.pluginpresets;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.pluginpresets.ui.PluginPresetsPluginPanel;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import static net.runelite.client.RuneLite.RUNELITE_DIR;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

@PluginDescriptor(
	name = "Plugin Presets",
	description = "Create presets of your plugin configurations.",
	tags = {"preset", "setups", "plugins"}
)
public class PluginPresetsPlugin extends Plugin
{
	public static final File PRESETS_DIR = new File(RUNELITE_DIR, "presets");
	public static final String HELP_LINK = "https://github.com/antero111/plugin-presets#using-plugin-presets";
	public static final String DEFAULT_PRESET_NAME = "Preset";
	protected static final List<String> IGNORED_PLUGINS = Stream.of("Plugin Presets", "Configuration", "Xtea").collect(Collectors.toList());
	protected static final List<String> IGNORED_KEYS = Stream.of("channel", "oauth", "username", "notesData").collect(Collectors.toList());
	private static final String PLUGIN_NAME = "Plugin Presets";
	private static final String ICON_FILE = "panel_icon.png";
	private static final String CONFIG_GROUP = "pluginpresets";
	private static final String CONFIG_KEY = "presets";

	@Getter
	private final HashMap<Keybind, PluginPreset> keybinds = new HashMap<>();

	@Getter
	@Setter
	private List<PluginPreset> pluginPresets = new ArrayList<>();

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RuneLiteConfig runeLiteConfig;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private KeyManager keyManager;

	private NavigationButton navigationButton;

	private PluginPresetsPluginPanel pluginPanel;

	private PluginPresetsSharingManager sharingManager;

	@Getter
	private PluginPresetsPresetManager presetManager;

	private final KeyListener keybindListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
			// Ignore
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			PluginPreset preset = keybinds.get(new Keybind(e));
			if (preset != null)
			{
				loadPreset(preset);
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			// Ignore
		}
	};

	private PluginPresetsStorage presetStorage;

	@Getter
	@Setter
	private PluginPresetsPresetEditor presetEditor = null;

	@Getter
	private Boolean loggedIn = false; // Used to inform that keybinds don't work in login screen

	@Override
	protected void startUp()
	{
		PluginPresetsStorage.createPresetFolder();

		pluginPanel = new PluginPresetsPluginPanel(this);
		sharingManager = new PluginPresetsSharingManager(this, pluginPanel);
		presetManager = new PluginPresetsPresetManager(this, pluginManager, configManager, runeLiteConfig);
		presetStorage = new PluginPresetsStorage(presetManager);

		loadPresets();
		savePresets();
		rebuildPluginUi();

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), ICON_FILE);

		navigationButton = NavigationButton.builder()
			.tooltip(PLUGIN_NAME)
			.priority(8)
			.icon(icon)
			.panel(pluginPanel)
			.popup(ImmutableMap
				.<String, Runnable>builder()
				.put("Open preset folder...", () ->
					LinkBrowser.open(PRESETS_DIR.toString()))
				.build())
			.build();
		clientToolbar.addNavigation(navigationButton);

		keyManager.registerKeyListener(keybindListener);
	}

	@Override
	protected void shutDown()
	{
		pluginPresets.clear();
		clientToolbar.removeNavigation(navigationButton);
		keyManager.unregisterKeyListener(keybindListener);
		presetStorage.deletePresetFolderIfEmpty();

		pluginPanel = null;
		sharingManager = null;
		presetManager = null;
		presetStorage = null;
		navigationButton = null;
	}

	@Subscribe
	public void onExternalPluginsChanged(ExternalPluginsChanged externalPluginsChanged)
	{
		SwingUtilities.invokeLater(this::rebuildPluginUi);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (validConfigChange(configChanged))
		{
			SwingUtilities.invokeLater(this::rebuildPluginUi);
		}
	}

	private boolean validConfigChange(ConfigChanged configChanged)
	{
		return !configChanged.getKey().equals("pluginpresetsplugin");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		loggedIn = event.getGameState() == GameState.LOGGED_IN;
		SwingUtilities.invokeLater(this::rebuildPluginUi);
	}

	public List<PluginPreset> getMatchingPresets()
	{
		return presetManager.getMatchingPresets();
	}

	public void updateConfig()
	{
		List<PluginPreset> syncPresets = pluginPresets.stream()
			.filter(preset -> !preset.getLocal())
			.collect(Collectors.toList());

		syncPresets.forEach(preset -> preset.setLocal(null));

		if (syncPresets.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY);
		}
		else
		{
			final String json = gson.toJson(syncPresets);
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY, json);
		}

		syncPresets.forEach(preset -> preset.setLocal(false));
	}

	private void loadConfig(String json)
	{
		if (Strings.isNullOrEmpty(json))
		{
			return;
		}

		final List<PluginPreset> configPresetData = gson.fromJson(json, new TypeToken<ArrayList<PluginPreset>>()
		{
		}.getType());

		configPresetData.forEach(preset -> preset.setLocal(false));
		pluginPresets.addAll(configPresetData);
	}

	public void createPreset(String presetName, boolean empty)
	{
		PluginPreset preset = presetManager.createPluginPreset(presetName, empty);
		pluginPresets.add(preset);

		savePresets();
		refreshPresets();
	}

	@SneakyThrows
	public void savePresets()
	{
		presetStorage.savePresets(pluginPresets);
		updateConfig();
	}

	public void refreshPresets()
	{
		pluginPresets.clear();
		loadPresets();
		savePresets();
		rebuildPluginUi();
	}

	@SneakyThrows
	public void loadPreset(final PluginPreset preset)
	{
		presetManager.loadPreset(preset);
	}

	public void deletePreset(final PluginPreset preset)
	{
		pluginPresets.remove(preset);
		savePresets();
		refreshPresets();
	}

	@SneakyThrows
	public void loadPresets()
	{
		pluginPresets.addAll(presetStorage.loadPresets());
		loadConfig(configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY));
		pluginPresets.sort(Comparator.comparing(PluginPreset::getName)); // Keep presets in order
		cacheKeybins();
	}

	private void cacheKeybins()
	{
		keybinds.clear();
		pluginPresets.forEach(preset ->
		{
			Keybind keybind = preset.getKeybind();
			if (keybind != null && keybinds.get(keybind) == null)
			{
				keybinds.put(keybind, preset);
			}
		});
	}

	public void updatePreset(PluginPreset preset)
	{
		pluginPresets.removeIf(pluginPreset -> pluginPreset.getId() == preset.getId());
		pluginPresets.add(preset);

		savePresets();
		refreshPresets();
	}

	public void importPresetFromClipboard()
	{
		PluginPreset newPreset = sharingManager.importPresetFromClipboard();
		if (newPreset != null)
		{
			pluginPresets.add(newPreset);
			savePresets();
			refreshPresets();
		}

	}

	public void exportPresetToClipboard(final PluginPreset preset)
	{
		sharingManager.exportPresetToClipboard(preset);
	}

	public void rebuildPluginUi()
	{
		pluginPanel.rebuild();
	}

	public void editPreset(PluginPreset preset)
	{
		setPresetEditor(new PluginPresetsPresetEditor(this, preset));
		rebuildPluginUi();
	}

	public void stopEdit()
	{
		setPresetEditor(null);
		rebuildPluginUi();
	}
}
