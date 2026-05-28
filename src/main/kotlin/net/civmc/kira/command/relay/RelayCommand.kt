package net.civmc.kira.command.relay

import com.github.maxopoly.kira.KiraMain
import com.github.maxopoly.kira.command.model.top.InputSupplier
import com.github.maxopoly.kira.rabbit.session.PermissionCheckSession
import com.github.maxopoly.kira.relay.GroupChatManager
import com.github.maxopoly.kira.relay.GroupId
import com.github.maxopoly.kira.relay.actions.GroupChatMessageAction
import com.github.maxopoly.kira.relay.actions.MinecraftLocation
import com.github.maxopoly.kira.relay.actions.NewPlayerAction
import com.github.maxopoly.kira.relay.actions.PlayerHitSnitchAction
import com.github.maxopoly.kira.relay.actions.SkynetAction
import com.github.maxopoly.kira.relay.actions.SkynetType
import com.github.maxopoly.kira.relay.actions.SnitchHitType
import com.github.maxopoly.kira.relay.actions.SnitchType
import com.github.maxopoly.kira.user.UserManager
import net.civmc.kira.command.Command
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import org.apache.logging.log4j.Logger
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class RelayCommand(logger: Logger, userManager: UserManager) : Command(logger, userManager) {

    override val name = "relay"

    private val weightFormat = DecimalFormat("##.##")
    private val sampleUuid = UUID.fromString("8326bc56-1ed9-40ff-8f24-46bf3e300e51")
    private val samplePlayer = "ttk2"
    private val sampleGroup = "exampleGroup"
    private val sampleSnitchName = "SecretBaseSnitch"
    private val sampleLocation get() = MinecraftLocation("world", 420, 100, 420)

    private fun sampleChatAction()  = GroupChatMessageAction(System.currentTimeMillis(), sampleGroup, samplePlayer, sampleUuid, "hello, this is an example message")
    private fun sampleSnitchEnter() = PlayerHitSnitchAction(System.currentTimeMillis(), samplePlayer, sampleUuid, sampleSnitchName, sampleGroup, sampleLocation, SnitchHitType.ENTER, SnitchType.ENTRY)
    private fun sampleSnitchLogin() = PlayerHitSnitchAction(System.currentTimeMillis(), samplePlayer, sampleUuid, sampleSnitchName, sampleGroup, sampleLocation, SnitchHitType.LOGIN, SnitchType.ENTRY)
    private fun sampleSnitchLogout() = PlayerHitSnitchAction(System.currentTimeMillis(), samplePlayer, sampleUuid, sampleSnitchName, sampleGroup, sampleLocation, SnitchHitType.LOGOUT, SnitchType.ENTRY)
    private fun sampleSkynetLogin()  = SkynetAction(System.currentTimeMillis(), samplePlayer, sampleUuid, SkynetType.LOGIN)
    private fun sampleSkynetLogout() = SkynetAction(System.currentTimeMillis(), samplePlayer, sampleUuid, SkynetType.LOGOUT)
    private fun sampleNewPlayer() = NewPlayerAction(System.currentTimeMillis(), samplePlayer, sampleUuid)

    override fun getCommandData(): SlashCommandData {
        return Commands.slash("relay", "Manage Discord ↔ Minecraft chat relays")
            .addSubcommands(
                SubcommandData("create-here", "Create a relay in this channel for a chat group")
                    .addOption(OptionType.STRING, "group", "Chat group name", true),
                SubcommandData("delete", "Delete a relay")
                    .addOption(OptionType.STRING, "group", "Chat group whose relay to delete", true),
                SubcommandData("list", "Show all relays you own"),
                SubcommandData("set-config", "Set the relay-config used for a specific relay")
                    .addOption(OptionType.STRING, "group", "Chat group", true)
                    .addOption(OptionType.STRING, "config", "Relay-config name", true),
                SubcommandData("info", "Show info about the relay in this channel"),
            )
            .addSubcommandGroups(
                SubcommandGroupData("config", "Manage relay configurations").addSubcommands(
                    SubcommandData("create", "Create a new relay configuration")
                        .addOption(OptionType.STRING, "name", "Configuration name", true),
                    SubcommandData("edit", "Configure properties of a relay configuration")
                        .addOption(OptionType.STRING, "name", "Configuration name", true)
                        .addOption(OptionType.STRING, "property", "Property to set (e.g. chatformat, showsnitches)", false)
                        .addOption(OptionType.STRING, "value", "Value to assign to the property", false)
                )
            )
    }

    override fun dispatchCommand(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        // Discord owners/admins bypass DefaultMemberPermissions.DISABLED — enforce in code too.
        if (event.guild?.idLong == KiraMain.getInstance().config.serverID &&
            !sender.hasPermission("admin")) {
            event.reply("You don't have permission to use /relay in this server")
                .setEphemeral(true).queue()
            return
        }
        val group = event.subcommandGroup
        val sub   = event.subcommandName
        when {
            group == "config" && sub == "create"  -> handleConfigCreate(event, sender)
            group == "config" && sub == "edit"    -> handleConfigEdit(event, sender)
            group == null    && sub == "create-here" -> handleCreateHere(event, sender)
            group == null    && sub == "delete"   -> handleDelete(event, sender)
            group == null    && sub == "list"     -> handleList(event, sender)
            group == null    && sub == "set-config"  -> handleSetConfig(event, sender)
            group == null    && sub == "info"     -> handleInfo(event, sender)
            else -> event.reply("Unknown subcommand").queue()
        }
    }

    // /relay create-here <group>
    // Port of CreateRelayChannelHereCommand. Touches Rabbit (requestRelayCreation) → deferReply.
    private fun handleCreateHere(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").queue()
            return
        }
        if (!sender.user.hasIngameAccount()) {
            event.reply("You need to link an ingame account first").queue()
            return
        }
        val groupArg = event.getOption("group")?.asString ?: run {
            event.reply("Missing group name").queue()
            return
        }
        val channelId = sender.channelID
        if (channelId <= -1) {
            event.reply("You can't do this from here").queue()
            return
        }
        val channel = KiraMain.getInstance().getJDA().getTextChannelById(channelId)
        if (channel == null) {
            event.reply("Something went wrong, tell an admin").queue()
            return
        }
        val servers = KiraMain.getInstance().getConfig().getServers()
        // groupArg is always the group (no server-selection in slash command — server optional in
        // prefix command was only needed because args were positional strings).
        val server = servers[0]

        event.deferReply().queue()
        try {
            val member = channel.guild.retrieveMemberById(sender.user.discordID).complete()
            val perms  = member.getPermissions(channel)
            if (!perms.contains(Permission.MANAGE_CHANNEL)) {
                event.hook.sendMessage("You need the 'MANAGE_CHANNEL' permission to add a relay to this channel").queue()
                return
            }
            KiraMain.getInstance().getMCRabbitGateway().requestRelayCreation(server, sender.user, groupArg, channel)
            event.hook.sendMessage("Checking permissions for channel handling...").queue()
        } catch (e: Exception) {
            event.hook.sendMessage("Something went wrong, tell and admin.").queue()
        }
    }

    // /relay delete <group>
    // Port of DeleteRelayCommand. Touches Rabbit (PermissionCheckSession) → deferReply.
    private fun handleDelete(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").queue()
            return
        }
        if (!sender.user.hasIngameAccount()) {
            event.reply("You need to link an ingame account first").queue()
            return
        }
        val groupArg = event.getOption("group")?.asString ?: run {
            event.reply("Missing group name").queue()
            return
        }
        val user = sender.user
        val man  = KiraMain.getInstance().getGroupChatManager()
        val servers = KiraMain.getInstance().getConfig().getServers()
        val server  = servers[0]
        val chat = man.getGroupChat(GroupId(server, groupArg.lowercase()))
        if (chat == null) {
            event.reply("No group chat with the name $groupArg on server `$server` is known").queue()
            return
        }
        event.deferReply().queue()
        KiraMain.getInstance().getRequestSessionManager().request(server,
            object : PermissionCheckSession(user.ingameUUID, chat.name,
                GroupChatManager.getNameLayerManageChannelPermission()) {
                override fun handlePermissionReply(hasPerm: Boolean) {
                    if (!hasPerm && !sender.hasPermission("admin")) {
                        event.hook.sendMessage("You do not have permission to delete this relay").queue()
                        return
                    }
                    val current = man.getGroupChat(GroupId(server, groupArg.lowercase()))
                    if (current == null) {
                        logger.warn("Failed to delete group chat $groupArg, it was already gone")
                        event.hook.sendMessage("Channel deletion failed, channel was already gone").queue()
                        return
                    }
                    logger.info("Attempting to delete group of chat for ${current.name} as initiated by $user")
                    KiraMain.getInstance().getGroupChatManager().deleteGroupChat(current)
                    event.hook.sendMessage("Successfully removed relay for group ${current.name}").queue()
                }
            })
        event.hook.sendMessage("Requesting permission confirmation from server...").queue()
    }

    // /relay list
    // Port of GetWeightCommand (identifier "getchannels") — lists all relays owned by the user with weights.
    // In-memory lookup via DAO → deferReply conservatively (DAO may hit DB).
    private fun handleList(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").queue()
            return
        }
        event.deferReply(true).queue()
        val user = sender.user
        val ownedChats = KiraMain.getInstance().getDAO().getGroupChatChannelIdByCreator(user)
        val reply = StringBuilder()
        var totalWeight = 0.0f
        var totalCount  = 0
        for (name in ownedChats) {
            val chat = KiraMain.getInstance().getGroupChatManager().getGroupChat(name) ?: continue
            totalCount++
            totalWeight += chat.weight
            reply.append("'${chat.name}' is in channel ${chat.discordChannelId} in guild ${chat.guildId}" +
                " with weight ${weightFormat.format(chat.weight)}\n")
        }
        reply.append("Total of $totalCount relay(s) owned with a total weight of ${weightFormat.format(totalWeight)}")
        event.hook.sendMessage(reply.toString()).queue()
    }

    // /relay set-config <group> <config>
    // Port of TieRelayConfigCommand. Touches Rabbit (PermissionCheckSession) → deferReply.
    private fun handleSetConfig(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").queue()
            return
        }
        if (!sender.user.hasIngameAccount()) {
            event.reply("You need to link an ingame account first").queue()
            return
        }
        val groupArg  = event.getOption("group")?.asString ?: run {
            event.reply("Missing group name").queue()
            return
        }
        val configArg = event.getOption("config")?.asString ?: run {
            event.reply("Missing config name").queue()
            return
        }
        val user    = sender.user
        val servers = KiraMain.getInstance().getConfig().getServers()
        val server  = servers[0]
        val chat = KiraMain.getInstance().getGroupChatManager().getGroupChat(GroupId(server, groupArg.lowercase()))
        if (chat == null) {
            event.reply("No group chat with the name $groupArg is known").queue()
            return
        }
        val config = KiraMain.getInstance().getRelayConfigManager().getByName(configArg)
        if (config == null) {
            event.reply("No relay config with the name $configArg is known").queue()
            return
        }
        event.deferReply().queue()
        KiraMain.getInstance().getRequestSessionManager().request(server,
            object : PermissionCheckSession(user.ingameUUID, chat.name,
                GroupChatManager.getNameLayerManageChannelPermission()) {
                override fun handlePermissionReply(hasPerm: Boolean) {
                    if (!hasPerm && !sender.hasPermission("admin")) {
                        event.hook.sendMessage("You do not have permission to set the config for this relay").queue()
                        return
                    }
                    KiraMain.getInstance().getGroupChatManager().setConfig(chat, config)
                    event.hook.sendMessage("Successfully set relay config for ${chat.name} to ${config.name}").queue()
                }
            })
        event.hook.sendMessage("Requesting permission confirmation from server...").queue()
    }

    // /relay info
    // Port of ChannelInfoCommand — shows all relays set up for the current channel.
    // In-memory lookup → no deferReply. Contains internal info (channel ID, owner, config) → ephemeral.
    private fun handleInfo(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").setEphemeral(true).queue()
            return
        }
        val channelId = sender.channelID
        if (channelId <= -1) {
            event.reply("You can't do this from here").setEphemeral(true).queue()
            return
        }
        val chats = KiraMain.getInstance().getGroupChatManager().getChatByChannelID(channelId)
        val reply = StringBuilder()
        reply.append("A total of ${chats.size} relays are setup for this channel\n")
        reply.append("Channel id: $channelId\n---\n")
        for (chat in chats) {
            reply.append("**${chat.name}**")
            reply.append("  Owner: ${chat.creator.name}\n")
            reply.append("  Config: ${chat.config.name}\n\n")
        }
        event.reply(reply.toString()).setEphemeral(true).queue()
    }

    // /relay config create <name>
    // Port of CreateRelayConfig. DB write (createRelayConfig) → deferReply conservatively.
    private fun handleConfigCreate(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").setEphemeral(true).queue()
            return
        }
        if (!sender.user.hasIngameAccount()) {
            event.reply("You need to link an ingame account first").setEphemeral(true).queue()
            return
        }
        val nameArg = event.getOption("name")?.asString ?: run {
            event.reply("Missing configuration name").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        val configMan = KiraMain.getInstance().getRelayConfigManager()
        if (configMan.getByName(nameArg) != null) {
            event.hook.sendMessage("A relay config with the given name already exists").queue()
            return
        }
        val config = configMan.createRelayConfig(nameArg, sender.user)
        if (config == null) {
            event.hook.sendMessage("Failed to create relay config, something went wrong").queue()
            return
        }
        event.hook.sendMessage("Successfully created relay config ${config.name}").queue()
    }

    // /relay config edit <name> [property] [value]
    // Port of ConfigureRelayConfigCommand. DB writes for property updates → deferReply.
    // Config details are owner-sensitive → ephemeral.
    private fun handleConfigEdit(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").setEphemeral(true).queue()
            return
        }
        val user = sender.user
        if (!user.hasIngameAccount()) {
            event.reply("You need to link an ingame account first").setEphemeral(true).queue()
            return
        }
        val nameArg     = event.getOption("name")?.asString ?: run {
            event.reply("Missing configuration name").setEphemeral(true).queue()
            return
        }
        val propertyArg = event.getOption("property")?.asString
        val valueArg    = event.getOption("value")?.asString

        event.deferReply(true).queue()
        val relayMan = KiraMain.getInstance().getRelayConfigManager()
        val relay    = relayMan.getByName(nameArg)
        if (relay == null) {
            event.hook.sendMessage("No relay config with this name is known").queue()
            return
        }
        val owner = KiraMain.getInstance().getUserManager().getUser(relay.ownerID)

        // No property or no value supplied → show config info (read-only; anyone with isauth may view).
        // Matches upstream Java which triggers show-info whenever args.length < 3.
        if (propertyArg == null || valueArg == null) {
            val reply = StringBuilder()
            reply.append("Relay config **${relay.name}** is owned by ")
            if (owner == null) reply.append("unknown user") else reply.append(owner.toNiceString())
            reply.append("\n - Relay chat from Discord to Minecraft (chatfromdiscord): ${relay.shouldRelayFromDiscord()}\n")
            reply.append(" - Relay chat from Minecraft to Discord (chattodiscord): ${relay.shouldRelayToDiscord()}\n")
            reply.append(" - Relay snitch alerts to Discord (showsnitches): ${relay.shouldShowSnitches()}\n")
            reply.append(" - Auto deletes discord messages (deletemessages): ${relay.shouldDeleteDiscordMessage()}\n")
            reply.append(" - Format used for group chat messages (chatformat): ${verbatimFormat(relay.chatFormat)}\n")
            reply.append("    Example: ${relay.formatChatMessage(sampleChatAction())}\n")
            reply.append(" - Format used for snitch alerts (snitchformat): ${verbatimFormat(relay.snitchFormat)}\n")
            reply.append("    Example: ${relay.formatSnitchOutput(sampleSnitchEnter())}\n")
            reply.append(" - Format used for entering a snitch range (snitchentermessage): ${verbatimFormat(relay.snitchEnterString)}\n")
            reply.append(" - Format used for logins within a snitch range (snitchloginmessage): ${verbatimFormat(relay.snitchLoginAction)}\n")
            reply.append(" - Format used for logouts within a snitch range (snitchloginmessage): ${verbatimFormat(relay.snitchLogoutAction)}\n")
            reply.append(" - Regex which will trigger an @ here ping for both chat messages and snitch alerts (hereformat): ${verbatimFormat(relay.hereFormat)}\n")
            reply.append(" - Regex which will trigger an @ everyone ping for both chat messages and snitch alerts (everyoneformat): ${verbatimFormat(relay.everyoneFormat)}\n")
            reply.append("- Time format used for the time stamps of messages (timeformat): ${verbatimFormat(relay.timeFormat)}\n")
            reply.append("    Example: ${relay.getFormattedTime(System.currentTimeMillis())}\n")
            reply.append(" - Is allowed to use @ here and @ everyone (ping): ${relay.shouldPing()}\n")
            reply.append(" - Relaying of logins/logout, referred to as Skynet enabled (skynetenabled): ${relay.isSkynetEnabled}\n")
            reply.append(" - Skynet format (skynetformat): ${verbatimFormat(relay.skynetFormat)}\n")
            reply.append("    Example: ${relay.formatSkynetMessage(sampleSkynetLogin())}\n")
            reply.append(" - Skynet login format (skynetloginformat): ${relay.skynetLoginString}\n")
            reply.append(" - Skynet logout format (skynetlogoutformat): ${relay.skynetLogoutString}\n")
            reply.append(" - Relaying of new player logins (newplayerenabled): ${relay.isNewPlayerEnabled}\n")
            reply.append(" - new player announcement format (newplayerformat): ${verbatimFormat(relay.newPlayerFormat)}\n")
            reply.append("    Example: ${relay.formatNewPlayerMessage(sampleNewPlayer())}\n")
            reply.append(" - Use \"help relayconfig\" for more information on how to configure these properties\n")
            event.hook.sendMessage(reply.toString()).queue()
            return
        }

        // Mutating a property requires ownership.
        if (relay.ownerID != user.id) {
            event.hook.sendMessage("You do not own this relay config and thus can not manage it").queue()
            return
        }
        val arguments = valueArg ?: ""
        val reply = StringBuilder()
        reply.append("Found relay with name ${relay.name} and confirmed permission check\n")

        when (propertyArg.lowercase()) {
            "chattodiscord" -> {
                val v = parseBool(arguments, reply)
                if (v != null) {
                    reply.append("Relaying chat from ingame to discord set to: $v\n")
                    relay.updateRelayToDiscord(v)
                }
            }
            "chatfromdiscord" -> {
                val v = parseBool(arguments, reply)
                if (v != null) {
                    reply.append("Relaying chat from discord to ingame set to: $v\n")
                    relay.updateRelayFromDiscord(v)
                }
            }
            "showsnitches" -> {
                val v = parseBool(arguments, reply)
                if (v != null) {
                    reply.append("Showing snitches set to: $v\n")
                    relay.updateShowSnitches(v)
                }
            }
            "deletemessages", "deletediscordmessages" -> {
                val v = parseBool(arguments, reply)
                if (v != null) {
                    reply.append("Deleting discord messages set to: $v\n")
                    relay.updateDeleteDiscordMessages(v)
                }
            }
            "chatformat" -> {
                if (passLengthCheck(arguments, 512, reply)) {
                    reply.append("Setting chat format to: $arguments\n")
                    relay.updateChatFormat(arguments)
                    reply.append("Example chat message would look like this:\n")
                    reply.append(relay.formatChatMessage(sampleChatAction()))
                    reply.append('\n')
                    checkPingAbility(relay, reply)
                }
            }
            "snitchformat" -> {
                if (passLengthCheck(arguments, 512, reply)) {
                    reply.append("Setting snitch alert format to: $arguments\n")
                    relay.setSnitchFormat(arguments)
                    reply.append("Example snitch message would look like this:\n")
                    reply.append(relay.formatSnitchOutput(sampleSnitchEnter()))
                    reply.append('\n')
                    checkPingAbility(relay, reply)
                }
            }
            "snitchloginmessage", "loginstring", "loginmessage" -> {
                if (passLengthCheck(arguments, 256, reply)) {
                    reply.append("Setting login message to: $arguments\n")
                    relay.updateLoginAction(arguments)
                    reply.append("Example snitch message would look like this:\n")
                    reply.append(sampleSnitchLogin())
                }
            }
            "snitchlogoutmessage", "logoutstring", "logoutmessage" -> {
                if (passLengthCheck(arguments, 256, reply)) {
                    reply.append("Setting logout message to: $arguments\n")
                    relay.updateLogoutAction(arguments)
                    reply.append(sampleSnitchLogout())
                }
            }
            "snitchentermessage", "enterstring", "entermessage" -> {
                if (passLengthCheck(arguments, 256, reply)) {
                    reply.append("Setting enter message to: $arguments\n")
                    relay.updateEnterAction(arguments)
                    reply.append(sampleSnitchEnter())
                }
            }
            "hereformat", "here" -> {
                if (passLengthCheck(arguments, 256, reply)) {
                    try {
                        Pattern.compile(arguments)
                        reply.append("Setting here trigger to: $arguments\n")
                        relay.updateHereFormat(arguments)
                    } catch (e: PatternSyntaxException) {
                        reply.append("$arguments is not a valid regex, see https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html for more information on how to write proper regex\n")
                    }
                }
            }
            "everyoneformat", "everyone" -> {
                if (passLengthCheck(arguments, 256, reply)) {
                    try {
                        Pattern.compile(arguments)
                        reply.append("Setting everyone trigger to: $arguments\n")
                        relay.updateEveryoneFormat(arguments)
                    } catch (e: PatternSyntaxException) {
                        reply.append("$arguments is not a valid regex, see https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html for more information on how to write proper regex\n")
                    }
                }
            }
            "ping", "shouldping" -> {
                val v = parseBool(arguments, reply)
                if (v != null) {
                    reply.append("Setting pinging to: $arguments\n")
                    relay.updateShouldPing(v)
                    checkPingAbility(relay, reply)
                }
            }
            "timeformat" -> {
                try {
                    DateTimeFormatter.ofPattern(arguments)
                    relay.updateTimeFormat(arguments)
                    reply.append("Setting time format to: $arguments\n")
                    reply.append("Example time stamp: ${relay.getFormattedTime(System.currentTimeMillis())}\n")
                } catch (e: IllegalArgumentException) {
                    reply.append("$arguments is not a valid time format, see https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html for more information on how to format time properly\n")
                }
            }
            "skynetenabled" -> {
                val v = parseBool(arguments, reply)
                if (v != null) {
                    relay.updateSkynetEnabled(v)
                    reply.append("Setting skynet status to: ${relay.isSkynetEnabled}")
                }
            }
            "skynetformat" -> {
                if (passLengthCheck(arguments, 512, reply)) {
                    reply.append("Setting skynet format to: $arguments\n")
                    relay.updateSkynetFormat(arguments)
                    reply.append("Example skynet message would look like this:\n")
                    reply.append(relay.formatSkynetMessage(sampleSkynetLogin()))
                    reply.append('\n')
                }
            }
            "skynetloginformat", "skynetloginstring" -> {
                if (passLengthCheck(arguments, 256, reply)) {
                    reply.append("Setting skynet login format to: $arguments\n")
                    relay.updateSkynetLoginString(arguments)
                    reply.append("Example skynet login message would look like this:\n")
                    reply.append(relay.formatSkynetMessage(sampleSkynetLogin()))
                    reply.append('\n')
                }
            }
            "skynetlogoutformat", "skynetlogoutstring" -> {
                if (passLengthCheck(arguments, 256, reply)) {
                    reply.append("Setting skynet logout format to: $arguments\n")
                    relay.updateSkynetLogoutString(arguments)
                    reply.append("Example skynet logout message would look like this:\n")
                    reply.append(relay.formatSkynetMessage(sampleSkynetLogout()))
                    reply.append('\n')
                }
            }
            "newplayerenabled" -> {
                val v = parseBool(arguments, reply)
                if (v != null) {
                    relay.updateNewPlayerEnabled(v)
                    reply.append("Setting new player announcements status to: ${relay.isNewPlayerEnabled}")
                }
            }
            "newplayerformat" -> {
                if (passLengthCheck(arguments, 512, reply)) {
                    reply.append("Setting new player announcements format to: $arguments\n")
                    relay.updateNewPlayerFormat(arguments)
                    reply.append("Example new player announcements message would look like this:\n")
                    reply.append(relay.formatNewPlayerMessage(sampleNewPlayer()))
                    reply.append('\n')
                }
            }
            else -> reply.append("$propertyArg is not a valid property to configure, see the command description for more information")
        }
        event.hook.sendMessage(reply.toString()).queue()
    }

    private fun parseBool(input: String, sb: StringBuilder): Boolean? {
        return when (input.lowercase()) {
            "0", "false", "f", "no"  -> false
            "1", "true",  "t", "y"   -> true
            else -> {
                sb.append("Could not parse $input as boolean\n")
                null
            }
        }
    }

    private fun passLengthCheck(input: String, maxLength: Int, reply: StringBuilder): Boolean {
        if (input.length > maxLength) {
            reply.append("Input exceeded the maximum allowed length. Allowed is up to $maxLength, but got ${input.length}")
            return false
        }
        return true
    }

    private fun checkPingAbility(config: com.github.maxopoly.kira.relay.RelayConfig, reply: StringBuilder) {
        if (!config.shouldPing()) return
        if (!config.snitchFormat.contains("%PING%")) {
            reply.append("Pinging is enabled, but your snitch format does not contain `%PING%` so no pings will actually be displayed for snitch alerts. Is this intended?\n")
        }
        if (!config.chatFormat.contains("%PING%")) {
            reply.append("Pinging is enabled, but your chat format does not contain `%PING%` so no pings will actually be displayed for chat messages. Is this intended?\n")
        }
    }

    private fun verbatimFormat(input: String) = "`` $input ``"
}
