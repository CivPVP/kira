package com.github.maxopoly.kira.rabbit.input;

import com.github.maxopoly.kira.relay.GroupId;
import org.json.JSONObject;

import com.github.maxopoly.kira.KiraMain;
import com.github.maxopoly.kira.rabbit.RabbitInputSupplier;
import com.github.maxopoly.kira.relay.GroupChat;
import com.github.maxopoly.kira.relay.GroupChatManager;
import com.github.maxopoly.kira.relay.actions.GroupChatMessageAction;
import com.github.maxopoly.kira.user.KiraUser;
import java.util.UUID;

public class SendGroupChatMessage extends RabbitMessage {

	public SendGroupChatMessage() {
		super("groupchatmessage");
	}

	@Override
	public void handle(JSONObject json, RabbitInputSupplier supplier) {
        String server = json.getString("server");
		String msg = json.getString("msg");
		String group = json.getString("group");
		UUID senderUUID = UUID.fromString(json.getString("senderUUID"));
		String sender;
		if (json.has("sender") && !json.isNull("sender")) {
			sender = json.getString("sender");
		} else {
			KiraUser linked = KiraMain.getInstance().getUserManager().getUserByIngameUUID(senderUUID);
			sender = linked != null && linked.getName() != null
					? linked.getName()
					: senderUUID.toString().substring(0, 8);
		}
		long timestamp = json.optLong("timestamp", System.currentTimeMillis());

		GroupChatMessageAction action = new GroupChatMessageAction(timestamp, group, sender, senderUUID, msg);
		KiraMain.getInstance().getAPISessionManager().handleGroupMessage(action);
		GroupChatManager man = KiraMain.getInstance().getGroupChatManager();
		GroupChat chat = man.getGroupChat(new GroupId(server, group.toLowerCase()));
		if (chat != null && chat.getConfig().shouldRelayToDiscord()) {
			chat.sendMessage(action);
		}
	}
}
