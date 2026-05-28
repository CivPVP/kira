package net.civmc.kira.command

import com.github.maxopoly.kira.KiraMain
import net.civmc.kira.command.admin.AdminCommand
import net.civmc.kira.command.api.ApiCommand
import net.civmc.kira.command.relay.RelayCommand
import net.civmc.kira.command.user.*
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions

object CommandManager {

    // TODO: Dependency Injection
    private val logger = KiraMain.getInstance().logger
    private val userManager = KiraMain.getInstance().userManager
    private val configManager = KiraMain.getInstance().config
    private val jda = KiraMain.getInstance().jda

    // TODO: Move this to config
    val devMode = false

    private val relayCommand = RelayCommand(logger, userManager)

    val commands = listOf(
            AdminCommand(logger, userManager),
            AuthCommand(logger, userManager),
            HelpCommand(logger, userManager),
            InfoCommand(logger, userManager),
            IngameCommand(logger, userManager),
            InviteCommand(logger, userManager),
            QuoteCommand(logger, userManager),
            UpdateRolesCommand(logger, userManager),
            WhoAmICommand(logger, userManager),
            relayCommand,
            ApiCommand(logger, userManager),
    )

    fun registerCommands() {
        // TODO: Handle error from updating commands
        jda.updateCommands()
                .addCommands(getGlobalCommands().map { it.getCommandData() })
                .queue()

        // Guild commands shadow globals of the same name, so a DISABLED-perms /relay
        // here hides it from non-admins on the main guild only.
        val relayShadow = relayCommand.getCommandData()
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED)

        jda.getGuildById(configManager.serverID)!!.updateCommands()
                .addCommands(getGuildCommands().map { it.getCommandData() } + relayShadow)
                .queue()

        jda.addEventListener(*commands.toTypedArray())
    }

    private fun getGlobalCommands(): List<Command> {
        if (devMode) {
            return emptyList()
        }

        return commands.filter { it.global }
    }

    private fun getGuildCommands(): List<Command> {
        if (devMode) {
            return commands;
        }

        return commands.filter { !it.global }
    }
}
