package net.civmc.kira.command.admin

import com.github.maxopoly.kira.KiraMain
import com.github.maxopoly.kira.command.model.top.InputSupplier
import com.github.maxopoly.kira.rabbit.session.RunConsoleCommandRequest
import com.github.maxopoly.kira.user.UserManager
import com.github.maxopoly.kira.util.CommandUtil
import net.civmc.kira.command.Command
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import org.apache.logging.log4j.Logger

class AdminCommand(logger: Logger, userManager: UserManager) : Command(logger, userManager) {

    override val name = "admin"
    override val global = false
    // requiredPermission stays "default"; per-subcommand checks are done in dispatch.
    // Top-level command is hidden by DefaultMemberPermissions.DISABLED — admins
    // must grant access explicitly via Server Settings → Integrations.

    override fun getCommandData(): SlashCommandData {
        return Commands.slash("admin", "Bot administration")
            .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
            .addSubcommands(
                SubcommandData("console", "Run a console command on Minecraft")
                    .addOption(OptionType.STRING, "command", "The command (optionally prefixed by server name)", true),
                SubcommandData("stop", "Shut down the bot")
            )
            .addSubcommandGroups(
                SubcommandGroupData("perms", "Permission management").addSubcommands(
                    SubcommandData("create-default", "Create default permission set"),
                    SubcommandData("give-default", "Assign the default role to users that have none, and auth role to ingame-linked users"),
                    SubcommandData("give-role", "Give a permission to a Kira role")
                        .addOption(OptionType.STRING, "role", "Kira role name", true)
                        .addOption(OptionType.STRING, "permission", "Permission name", true),
                    SubcommandData("list", "List a user's permissions")
                        .addOption(OptionType.USER, "user", "Discord user", true),
                    SubcommandData("reload", "Reload the permission system")
                ),
                SubcommandGroupData("role", "Role management").addSubcommands(
                    SubcommandData("give", "Give a Kira role to a user")
                        .addOption(OptionType.USER, "user", "Discord user", true)
                        .addOption(OptionType.STRING, "role", "Kira role name", true)
                ),
                SubcommandGroupData("user", "User management").addSubcommands(
                    SubcommandData("deauth", "Deauthenticate a user")
                        .addOption(OptionType.USER, "user", "Discord user", true),
                    SubcommandData("sync", "Re-sync a user's Discord state")
                        .addOption(OptionType.USER, "user", "Discord user", true)
                ),
                SubcommandGroupData("server", "Discord server management").addSubcommands(
                    SubcommandData("list", "List Discord servers Kira is in"),
                    SubcommandData("leave", "Leave a Discord server by ID")
                        .addOption(OptionType.STRING, "id", "Discord guild ID", true),
                    SubcommandData("ban", "Ban a Discord server by ID")
                        .addOption(OptionType.STRING, "id", "Discord guild ID", true),
                    SubcommandData("unban", "Unban a Discord server by ID")
                        .addOption(OptionType.STRING, "id", "Discord guild ID", true)
                ),
                SubcommandGroupData("relay", "Relay administration").addSubcommands(
                    SubcommandData("list-all", "List all relays across all users")
                )
            )
    }

    override fun dispatchCommand(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        val group = event.subcommandGroup
        val sub   = event.subcommandName
        when {
            group == null     && sub == "console"        -> handle("consoleop", event, sender, ::doConsole)
            group == null     && sub == "stop"           -> handle("admin",     event, sender, ::doStop)
            group == "perms"  && sub == "create-default" -> handle("admin",     event, sender, ::doPermsCreateDefault)
            group == "perms"  && sub == "give-default"   -> handle("admin",     event, sender, ::doPermsGiveDefault)
            group == "perms"  && sub == "give-role"      -> handle("admin",     event, sender, ::doPermsGiveRole)
            group == "perms"  && sub == "list"           -> handle("admin",     event, sender, ::doPermsList)
            group == "perms"  && sub == "reload"         -> handle("admin",     event, sender, ::doPermsReload)
            group == "role"   && sub == "give"           -> handle("admin",     event, sender, ::doRoleGive)
            group == "user"   && sub == "deauth"         -> handle("admin",     event, sender, ::doUserDeauth)
            group == "user"   && sub == "sync"           -> handle("admin",     event, sender, ::doUserSync)
            group == "server" && sub == "list"           -> handle("admin",     event, sender, ::doServerList)
            group == "server" && sub == "leave"          -> handle("admin",     event, sender, ::doServerLeave)
            group == "server" && sub == "ban"            -> handle("admin",     event, sender, ::doServerBan)
            group == "server" && sub == "unban"          -> handle("admin",     event, sender, ::doServerUnban)
            group == "relay"  && sub == "list-all"       -> handle("admin",     event, sender, ::doRelayListAll)
            else -> event.reply("Unknown subcommand").setEphemeral(true).queue()
        }
    }

    private fun handle(
        perm: String,
        event: SlashCommandInteractionEvent,
        sender: InputSupplier,
        body: (SlashCommandInteractionEvent, InputSupplier) -> Unit
    ) {
        if (!sender.hasPermission(perm)) {
            event.reply("You don't have the required permission to do this").setEphemeral(true).queue()
            logger.info("${sender.identifier} attempted to run forbidden command: admin ${event.subcommandGroup}/${event.subcommandName}")
            return
        }
        body(event, sender)
    }

    // /admin console <command>
    // Port of ConsoleCommand. Rabbit round-trip → deferReply.
    private fun doConsole(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.user.hasIngameAccount()) {
            event.reply("You need to have an in-game account linked to use this command").setEphemeral(true).queue()
            return
        }
        val argument = event.getOption("command")?.asString ?: run {
            event.reply("Missing command").setEphemeral(true).queue()
            return
        }
        event.deferReply().queue()
        val route = CommandUtil.getRoute(argument, KiraMain.getInstance().config.servers)
        KiraMain.getInstance().requestSessionManager
            .request(route.server(), RunConsoleCommandRequest(route.command(), sender.user.ingameUUID, sender))
        event.hook.sendMessage("Running command `${route.command()}` as console on server `${route.server()}`").queue()
    }

    // /admin stop
    // Port of StopCommand.
    private fun doStop(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        event.reply("Thank you and good bye").queue()
        KiraMain.getInstance().stop()
    }

    // /admin perms create-default
    // Port of CreateDefaultPermsCommand.
    private fun doPermsCreateDefault(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        event.deferReply(true).queue()
        KiraMain.getInstance().kiraRoleManager.setupDefaultPermissions()
        event.hook.sendMessage("Setup basic permissions").queue()
    }

    // /admin perms give-default
    // Port of GiveDefaultPermission. Iterates all users — DB hits → deferReply.
    // NOTE: the Java handler takes no args despite the original spec sketch implying a permission name.
    private fun doPermsGiveDefault(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        event.deferReply(true).queue()
        val roleMan = KiraMain.getInstance().kiraRoleManager
        val defaultRole = roleMan.defaultRole
        val sb = StringBuilder()
        KiraMain.getInstance().userManager.allUsers.forEach { u ->
            if (roleMan.getRoles(u).isEmpty()) {
                roleMan.giveRoleToUser(u, defaultRole)
                sb.append("Giving default role to ").append(u.toString()).append('\n')
            }
        }
        val authRole = roleMan.getRole("auth")
        KiraMain.getInstance().userManager.allUsers
            .filter { it.hasIngameAccount() }
            .forEach { u ->
                if (!roleMan.getRoles(u).contains(authRole)) {
                    roleMan.giveRoleToUser(u, authRole)
                    sb.append("Giving auth role to ").append(u.toString()).append('\n')
                }
            }
        val reply = sb.toString().ifEmpty { "No users needed updating" }
        event.hook.sendMessage(reply).queue()
    }

    // /admin perms give-role <role> <permission>
    // Port of GivePermissionToRoleCommand.
    private fun doPermsGiveRole(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val roleArg = event.getOption("role")?.asString ?: run {
            event.reply("Missing role").setEphemeral(true).queue()
            return
        }
        val permArg = event.getOption("permission")?.asString ?: run {
            event.reply("Missing permission").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        val roleMan = KiraMain.getInstance().kiraRoleManager
        val role = roleMan.getRole(roleArg)
        if (role == null) {
            event.hook.sendMessage("Role $roleArg not found").queue()
            return
        }
        val perm = roleMan.getPermission(permArg)
        if (perm == null) {
            event.hook.sendMessage("Permission $permArg not found").queue()
            return
        }
        roleMan.addPermissionToRole(role, perm, true)
        event.hook.sendMessage("Giving permission ${perm.name} to ${role.name}").queue()
    }

    // /admin perms list <user>
    // Port of ListPermissionsForUserCommand. Reveals another user's permissions → ephemeral.
    private fun doPermsList(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val targetUser = event.getOption("user")?.asUser ?: run {
            event.reply("Missing user").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        val sb = StringBuilder()
        val roleMan = KiraMain.getInstance().kiraRoleManager
        val user = KiraMain.getInstance().userManager.parseUser(targetUser.idLong.toString(), sb)
        if (user == null) {
            sb.append("User not found")
            event.hook.sendMessage(sb.toString()).queue()
            return
        }
        val roles = roleMan.getRoles(user)
        if (roles.isEmpty()) {
            sb.append("This user has no rules\n")
        }
        for (role in roles) {
            sb.append("From ").append(role.name).append(":\n")
            for (perm in role.allPermissions) {
                sb.append(" - ").append(perm.name).append('\n')
            }
        }
        event.hook.sendMessage(sb.toString()).queue()
    }

    // /admin perms reload
    // Port of ReloadPermissionCommand. DB read → deferReply.
    private fun doPermsReload(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        event.deferReply(true).queue()
        val roleMan = KiraMain.getInstance().dao.loadAllRoles()
        KiraMain.getInstance().kiraRoleManager.reload(roleMan)
        event.hook.sendMessage("Successfully reloaded permissions").queue()
    }

    // /admin role give <user> <role>
    // Port of GiveRoleCommand.
    private fun doRoleGive(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val targetUser = event.getOption("user")?.asUser ?: run {
            event.reply("Missing user").setEphemeral(true).queue()
            return
        }
        val roleArg = event.getOption("role")?.asString ?: run {
            event.reply("Missing role").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        val sb = StringBuilder()
        val roleMan = KiraMain.getInstance().kiraRoleManager
        val role = roleMan.getRole(roleArg)
        if (role == null) {
            sb.append("Role $roleArg not found")
            event.hook.sendMessage(sb.toString()).queue()
            return
        }
        val user = KiraMain.getInstance().userManager.parseUser(targetUser.idLong.toString(), sb)
        if (user == null) {
            sb.append("User not found")
            event.hook.sendMessage(sb.toString()).queue()
            return
        }
        if (roleMan.getRoles(user).contains(role)) {
            sb.append(user.toString()).append(" already has role ").append(role.name)
        } else {
            roleMan.giveRoleToUser(user, role)
            sb.append("Giving role ").append(role.name).append(" to ").append(user.toString())
        }
        event.hook.sendMessage(sb.toString()).queue()
    }

    // /admin user deauth <user>
    // Port of DeauthDiscordCommand. JDA REST call inside whenComplete → deferReply.
    // Upstream had an async-result race: the StringBuilder was returned sync but mutated
    // by whenComplete after the return. We send a single follow-up inside whenComplete
    // that includes both the parseUser prefix and the role-removal result.
    private fun doUserDeauth(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val targetUser = event.getOption("user")?.asUser ?: run {
            event.reply("Missing user").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        val prefix = StringBuilder()
        val userManager = KiraMain.getInstance().userManager
        val authManager = KiraMain.getInstance().discordRoleManager
        val user = userManager.parseUser(targetUser.idLong.toString(), prefix)
        if (user == null) {
            prefix.append("User not found, no action was taken\n")
            event.hook.sendMessage(prefix.toString()).queue()
            return
        }
        val prefixStr = prefix.toString()
        authManager.takeDiscordRole(KiraMain.getInstance().guild, user)
            .whenComplete { worked, _ ->
                val out = StringBuilder(prefixStr)
                out.append("Unregistered user with given id found in discord, role removal ")
                    .append(if (worked) "successfull" else "unsuccessfull").append('\n')
                if (worked) {
                    user.updateIngame(null, null)
                    KiraMain.getInstance().dao.updateUser(user)
                }
                event.hook.sendMessage(out.toString()).queue()
            }
    }

    // /admin user sync <user>
    // Port of SyncUserCommand.
    private fun doUserSync(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val targetUser = event.getOption("user")?.asUser ?: run {
            event.reply("Missing user").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        val sb = StringBuilder()
        val user = KiraMain.getInstance().userManager.parseUser(targetUser.idLong.toString(), sb)
        if (user == null) {
            sb.append("User not found")
            event.hook.sendMessage(sb.toString()).queue()
            return
        }
        KiraMain.getInstance().discordRoleManager.syncUser(user)
        sb.append("Syncing user ").append(user)
        event.hook.sendMessage(sb.toString()).queue()
    }

    // /admin server list
    // Port of ListDiscordServersCommand.
    private fun doServerList(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        event.deferReply(true).queue()
        val botServers = KiraMain.getInstance().jda.guilds
        if (botServers.isNullOrEmpty()) {
            event.hook.sendMessage("Kira is not in any Discord servers.").queue()
            return
        }
        val response = StringBuilder("Kira is currently in: \n• [server id]: server name")
        for (server in botServers) {
            response.append('\n')
                .append("• Server[`")
                .append(server.name)
                .append("`:`")
                .append(server.id)
                .append("`]")
        }
        event.hook.sendMessage(response.toString()).queue()
    }

    // /admin server leave <id>
    // Port of LeaveDiscordServerCommand. Async leave-result follow-ups go through the
    // ephemeral interaction hook so admin actions don't leak to the channel.
    private fun doServerLeave(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val idArg = event.getOption("id")?.asString ?: run {
            event.reply("Missing id").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        val foundServer = KiraMain.getInstance().jda.getGuildById(idArg)
        if (foundServer == null) {
            event.hook.sendMessage("Kira is not in that server.").queue()
            return
        }
        event.hook.sendMessage("Leave operation queued.").queue()
        foundServer.leave().queue(
            { event.hook.sendMessage("Successfully left $idArg").setEphemeral(true).queue() },
            { event.hook.sendMessage("Was unable to leave $idArg").setEphemeral(true).queue() }
        )
    }

    // /admin server ban <id>
    // Port of ManageDiscordBansCommand "BAN" branch. Async leave-result follow-up routed
    // through the ephemeral hook (same secrecy rationale as doServerLeave).
    private fun doServerBan(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val idArg = event.getOption("id")?.asString ?: run {
            event.reply("Missing id").setEphemeral(true).queue()
            return
        }
        val serverID = try {
            java.lang.Long.parseUnsignedLong(idArg)
        } catch (e: NumberFormatException) {
            event.reply("That Discord server ID was invalid.").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        KiraMain.getInstance().dao.banServer(serverID)
        event.hook.sendMessage("That Discord server has now been banned").queue()
        val discordServer = KiraMain.getInstance().jda.getGuildById(serverID)
        if (discordServer != null) {
            discordServer.leave().queue(
                { event.hook.sendMessage("Kira has also left that server.").setEphemeral(true).queue() },
                { event.hook.sendMessage("Kira could not leave that server. Try again with /admin server leave").setEphemeral(true).queue() }
            )
        }
    }

    // /admin server unban <id>
    // Port of ManageDiscordBansCommand "UNBAN" branch.
    private fun doServerUnban(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        val idArg = event.getOption("id")?.asString ?: run {
            event.reply("Missing id").setEphemeral(true).queue()
            return
        }
        val serverID = try {
            java.lang.Long.parseUnsignedLong(idArg)
        } catch (e: NumberFormatException) {
            event.reply("That Discord server ID was invalid.").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue()
        KiraMain.getInstance().dao.unbanServer(serverID)
        event.hook.sendMessage("That Discord server has now been unbanned.").queue()
    }

    // /admin relay list-all
    // Port of ListDiscordRelaysCommand.
    private fun doRelayListAll(event: SlashCommandInteractionEvent, @Suppress("UNUSED_PARAMETER") sender: InputSupplier) {
        event.deferReply(true).queue()
        val groupChatManager = KiraMain.getInstance().groupChatManager
        val groupChats = groupChatManager.groupChats
        if (groupChats.isNullOrEmpty()) {
            event.hook.sendMessage("Kira is not serving any relays.").queue()
            return
        }
        val discordBot = KiraMain.getInstance().jda
        val response = StringBuilder("Kira is currently serving relays:")
        for (groupChat in groupChats) {
            val server  = discordBot.getGuildById(groupChat.guildId)
            val channel = server?.getTextChannelById(groupChat.discordChannelId)
            response.append('\n')
                .append("• Relay[`").append(groupChat.id).append("`] ")
                .append("Server[").append(server?.name).append(":`").append(groupChat.guildId).append("`] ")
                .append("Channel[").append(channel?.name).append(":`").append(groupChat.discordChannelId).append("`] ")
                .append("Group[").append(groupChat.name).append("]")
        }
        event.hook.sendMessage(response.toString()).queue()
    }
}
