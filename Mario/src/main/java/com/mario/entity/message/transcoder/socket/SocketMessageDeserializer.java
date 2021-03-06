package com.mario.entity.message.transcoder.socket;

import com.mario.entity.message.MessageRW;
import com.mario.entity.message.SocketMessage;
import com.mario.entity.message.transcoder.binary.BinaryMessageDeserializer;
import com.mario.gateway.socket.SocketMessageType;
import com.nhb.common.data.PuArrayList;
import com.nhb.common.data.PuElement;
import com.nhb.common.data.PuObject;

public class SocketMessageDeserializer extends BinaryMessageDeserializer {

	@Override
	public void decode(Object data, MessageRW message) {
		if (data instanceof byte[]) {
			super.decode(data, message);
		} else if (data instanceof Object[]) {
			Object[] arr = (Object[]) data;
			Object body = arr[0];

			if (body instanceof byte[]) {
				super.decode(body, message);
			} else if (body instanceof PuElement) {
				message.setData((PuElement) body);
			} else if (body instanceof String) {
				// websocket support
				String bodyString = (String) body;
				bodyString = bodyString.trim();
				if (((String) body).startsWith("{")) {
					message.setData(PuObject.fromJSON(bodyString));
				} else if (bodyString.startsWith("[")) {
					message.setData(PuArrayList.fromJSON(bodyString));
				} else {
					throw new RuntimeException("Unrecognized body data: " + bodyString);
				}
			}

			if (message instanceof SocketMessage) {
				if (arr.length > 1) {
					String sessionId = (String) arr[1];
					((SocketMessage) message).setSessionId(sessionId);
					if (arr.length > 2) {
						SocketMessageType socketMessageType = (SocketMessageType) arr[2];
						((SocketMessage) message).setSocketMessageType(socketMessageType);
					}
				}
			}
		}
	}
}
