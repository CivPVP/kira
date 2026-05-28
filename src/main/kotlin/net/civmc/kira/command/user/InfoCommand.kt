package net.civmc.kira.command.user

import com.github.maxopoly.kira.command.model.top.InputSupplier
import com.github.maxopoly.kira.user.UserManager
import net.civmc.kira.command.Command
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.apache.logging.log4j.Logger

class InfoCommand(logger: Logger, userManager: UserManager) : Command(logger, userManager) {

    override val name = "info"
    override val global = false

    override fun dispatchCommand(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        event.reply("Hello, I am Kira. I facilitate communication between discord and minecraft servers. "
                + "I was created by Maxopoly#3569 and my source code can be found here: https://github.com/maxopoly/kira").queue()
    }

    override fun getCommandData() = Commands.slash("info", "Prints basic info on the bot")
}
