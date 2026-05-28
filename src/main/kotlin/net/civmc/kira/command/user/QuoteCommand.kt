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
        event.deferReply().queue()
        val reply = try {
            val quote = quoteHandler.getQuote()
            quote.getQuote() + "\n" + " - " + quote.getAuthor()
        } catch (e: Exception) {
            logger.error("Failed to fetch quote", e)
            "Sometimes things dont go the way we expect them to"
        }
        event.hook.editOriginal(reply).queue()
    }

    override fun getCommandData() = Commands.slash("quote", "Gives life advice")
}
