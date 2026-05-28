package net.civmc.kira.listener

import com.github.maxopoly.kira.KiraMain
import com.github.maxopoly.kira.user.UserManager
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class RelayMessageListener(
    private val userManager: UserManager,
    private val ownID: Long,
) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!isValidDiscordAccount(event.author)) return
        // PM-as-command intentionally removed; DM support is a deferred follow-up.
        if (event.isFromType(ChannelType.PRIVATE)) return

        val user = userManager.getOrCreateUserByDiscordID(event.author.idLong)
        if (!user.hasIngameAccount()) return

        val chatMan = KiraMain.getInstance().groupChatManager
        val chats = chatMan.getChatByChannelID(event.channel.idLong)
        if (chats.isEmpty()) return

        val message = sanitize(event.message.contentDisplay)
        if (message.isEmpty()) return

        var delete = false
        for (chat in chats) {
            if (chat.config.shouldRelayFromDiscord()) {
                KiraMain.getInstance().mcRabbitGateway
                    .sendGroupChatMessage(chat.server, user, chat, message)
            }
            if (chat.config.shouldDeleteDiscordMessage()) {
                delete = true
            }
        }
        if (delete) {
            event.message.delete().queue()
        }
    }

    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        if (!isValidDiscordAccount(event.user)) return
        val user = userManager.getOrCreateUserByDiscordID(event.user.idLong)
        if (user.hasIngameAccount()) {
            KiraMain.getInstance().discordRoleManager
                .giveDiscordRole(KiraMain.getInstance().guild, user)
        }
    }

    override fun onGuildJoin(event: GuildJoinEvent) {
        val discordServer = event.guild
        if (KiraMain.getInstance().dao.isServerBanned(discordServer.idLong)) {
            discordServer.leave().queue()
        }
    }

    private fun isValidDiscordAccount(user: User?): Boolean {
        if (user == null) return false
        return !(user.isBot || user.idLong == ownID)
    }

    private fun sanitize(input: String): String {
        var result = input
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .replace("§", "")
            .trim()
        if (result.length > 255) {
            result = result.substring(0, 255)
        }
        return result
    }
}
