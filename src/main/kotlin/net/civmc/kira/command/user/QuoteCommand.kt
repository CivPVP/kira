package net.civmc.kira.command.user

import com.github.maxopoly.kira.command.model.top.InputSupplier
import com.github.maxopoly.kira.user.UserManager
import com.github.maxopoly.kira.util.QuoteHandler
import net.civmc.kira.command.Command
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.apache.logging.log4j.Logger

class QuoteCommand(logger: Logger, userManager: UserManager) : Command(logger, userManager) {

    override val name = "quote"
    override val requiredPermission = "isauth"
    override val global = false

    private val quoteHandler = QuoteHandler()

    override fun dispatchCommand(event: SlashCommandInteractionEvent, sender: InputSupplier) {
        val quote = try {
            quoteHandler.getQuote()
        } catch (e: Exception) {
            e.printStackTrace()
            event.reply("Sometimes things dont go the way we expect them to").queue()
            return
        }
        event.reply(quote.getQuote() + "\n" + " - " + quote.getAuthor()).queue()
    }

    override fun getCommandData() = Commands.slash("quote", "Gives life advice")
}
