package net.civmc.kira.command.api

import com.github.maxopoly.kira.KiraMain
import com.github.maxopoly.kira.api.token.APIDataType
import com.github.maxopoly.kira.command.model.top.InputSupplier
import com.github.maxopoly.kira.rabbit.session.APIPermissionRequest
import com.github.maxopoly.kira.user.UserManager
import net.civmc.kira.command.Command
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import org.apache.logging.log4j.Logger

class ApiCommand(logger: Logger, userManager: UserManager) : Command(logger, userManager) {

    override val name = "api"
    override val global = false
    // requiredPermission stays "default"; per-subcommand checks happen in dispatchCommand.

    override fun getCommandData(): SlashCommandData {
        return Commands.slash("api", "API token management").addSubcommandGroups(
            SubcommandGroupData("token", "Manage API tokens").addSubcommands(
                // Note: upstream's prefix command accepted a list of types in a single
                // call. Discord slash commands don't naturally model variadic options,
                // so this port simplifies to one type per invocation. Users wanting
                // multiple permissions on one token will need multiple invocations.
                SubcommandData("new", "Generate a new API token")
                    .addOption(OptionType.STRING, "type", "Token type (SNITCH, CHAT, or SKYNET)", true)
                    .addOption(OptionType.STRING, "server", "Target server name (default: first configured)", false),
                SubcommandData("list", "List your unused API tokens"),
                SubcommandData("revoke", "Revoke an API token by its index from /api token list")
                    .addOption(OptionType.INTEGER, "index", "The index of the token to revoke", true)
            )
        )
    }

    override fun dispatchCommand(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (event.subcommandGroup != "token") {
            event.reply("Unknown subcommand group").setEphemeral(true).queue()
            return
        }
        when (event.subcommandName) {
            "new"    -> handleNew(event, sender)
            "list"   -> handleList(event, sender)
            "revoke" -> handleRevoke(event, sender)
            else     -> event.reply("Unknown subcommand").setEphemeral(true).queue()
        }
    }

    private fun handleNew(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").setEphemeral(true).queue()
            return
        }
        if (!sender.user.hasIngameAccount()) {
            event.reply("You need to have an in-game account linked to use this command").setEphemeral(true).queue()
            return
        }
        val typeArg = event.getOption("type")?.asString ?: run {
            event.reply("Missing token type").setEphemeral(true).queue()
            return
        }
        val dataType = try {
            APIDataType.valueOf(typeArg.uppercase())
        } catch (e: IllegalArgumentException) {
            event.reply("$typeArg is not a valid data type, allowed ones are: ${APIDataType.values().toList()}")
                .setEphemeral(true).queue()
            return
        }
        val servers = KiraMain.getInstance().config.servers
        val serverName = event.getOption("server")?.asString
        val target = if (serverName != null) {
            servers.firstOrNull { it.equals(serverName, ignoreCase = true) } ?: run {
                event.reply("Unknown server: $serverName. Configured servers: ${servers.toList()}")
                    .setEphemeral(true).queue()
                return
            }
        } else {
            servers[0]
        }
        KiraMain.getInstance().getRequestSessionManager()
            .request(target, APIPermissionRequest(sender.user.ingameUUID, sender, listOf(dataType), -1))
        // FIXME: async token delivery via supplier.reportBack() posts the token
        // publicly to the channel. Making it ephemeral requires routing the async
        // callback through the interaction hook (deferReply + editOriginal) and
        // plumbing the hook into the supplier or APIPermissionRequest callback.
        //
        // Cleanest fix: introduce an InteractionHookInputSupplier in
        // net.civmc.kira.command that wraps an InteractionHook and routes
        // reportBack() to hook.sendMessage(msg).setEphemeral(true).queue();
        // then have Command.onSlashCommandInteraction construct that supplier
        // (instead of DiscordCommandChannelSupplier) for slash invocations.
        // APIPermissionRequest stays unchanged.
        //
        // Tracked as follow-up; for now this matches upstream behavior.
        event.reply("Contacting ingame server to retrieve group permission data").setEphemeral(true).queue()
    }

    private fun handleList(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").setEphemeral(true).queue()
            return
        }
        val tokens = KiraMain.getInstance().getAPISessionManager().getTokenManager()
            .getTokensForUser(sender.user)
        if (tokens.isEmpty()) {
            event.reply("You have no open tokens").setEphemeral(true).queue()
            return
        }
        val sb = StringBuilder()
        sb.append("You have a total of ")
        sb.append(tokens.size)
        sb.append(" open tokens:\n")
        var counter = 1
        for (token in tokens) {
            sb.append(counter++)
            sb.append(" - Secret: ")
            sb.append(token.secret)
            val expireTime = token.expirationTime
            if (expireTime == -1L) {
                sb.append(", Expires never\n")
            } else {
                sb.append(", Expires in: ")
                sb.append((expireTime - System.currentTimeMillis()) / (1000 * 60))
                sb.append(" minutes")
                sb.append("\n")
            }
        }
        event.reply(sb.toString()).setEphemeral(true).queue()
    }

    private fun handleRevoke(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        if (!sender.hasPermission("isauth")) {
            event.reply("You don't have the required permission to do this").setEphemeral(true).queue()
            return
        }
        val index = event.getOption("index")?.asInt ?: run {
            event.reply("Missing index").setEphemeral(true).queue()
            return
        }
        val tokens = KiraMain.getInstance().getAPISessionManager().getTokenManager()
            .getTokensForUser(sender.user)
        if (index > tokens.size || index <= 0) {
            event.reply("You can not delete token $index, because there are only ${tokens.size} tokens total")
                .setEphemeral(true).queue()
            return
        }
        val token = tokens[index - 1]
        KiraMain.getInstance().getAPISessionManager().getTokenManager().removeToken(token)
        event.reply("Deleted token with index '$index' and secret '${token.secret}'").setEphemeral(true).queue()
    }
}
