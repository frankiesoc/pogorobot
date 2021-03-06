/**
 Copyright 2019 Benjamin Marstaller
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 
    http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

package pogorobot.telegram;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.extensions.bots.commandbot.TelegramLongPollingCommandBot;
import org.telegram.telegrambots.extensions.bots.commandbot.commands.IBotCommand;
import org.telegram.telegrambots.meta.ApiContext;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Location;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResult;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiValidationException;

import pogorobot.entities.User;
import pogorobot.events.telegrambot.IncomingManualRaid;
import pogorobot.service.MessageContentProcessor;
import pogorobot.service.TelegramKeyboardService;
import pogorobot.service.TelegramMessageCreatorService;
import pogorobot.service.UserService;
import pogorobot.telegram.config.Configuration;
import pogorobot.telegram.util.Emoji;
import pogorobot.telegram.util.Type;
import pogorobot.util.RaidImageScanner;

/**
 * PogoBot contains a queue for delayed sending of messages if there are too
 * many.
 *
 */
public class PogoBot extends TelegramLongPollingCommandBot implements TelegramBot {

	Logger logger = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private UserService userService;

	@Autowired
	private MessageContentProcessor messageContentProcessor;

	@Autowired
	private TelegramMessageCreatorService telegramHandlerService;

	@Autowired
	private RaidImageScanner raidImageScanner;

	private static final long MANY_CHATS_SEND_INTERVAL = 33;
	private static final long ONE_CHAT_SEND_INTERVAL = 1000;
	private static final long CHAT_INACTIVE_INTERVAL = 1000 * 60 * 10L;
	private final Timer mSendTimer = new Timer(true);
	private final ConcurrentHashMap<Long, MessageQueue> mMessagesMap = new ConcurrentHashMap<>(32, 0.75f, 1);
	private final ArrayList<MessageQueue> mSendQueues = new ArrayList<>();
	private final AtomicBoolean mSendRequested = new AtomicBoolean(false);

	private String bottoken;

	private static Configuration configuration;


	private final class MessageSenderTask extends TimerTask {
		@Override
		public void run() {
			// mSendRequested used for optimisation to not traverse all
			// mMessagesMap 30 times per second all the time
			if (!mSendRequested.getAndSet(false))
				return;

			long currentTime = System.currentTimeMillis();
			mSendQueues.clear();
			boolean processNext = false;

			// First step - find all chats in which already allowed to send
			// message (passed more than 1000 ms from previuos send)
			Iterator<Map.Entry<Long, MessageQueue>> it = mMessagesMap.entrySet().iterator();
			while (it.hasNext()) {
				MessageQueue queue = it.next().getValue();
				int state = queue.getCurrentState(currentTime); // Actual check
																// here
				if (state == MessageQueue.GET_MESSAGE) {
					mSendQueues.add(queue);
					processNext = true;
				} else if (state == MessageQueue.WAIT_SIG) {
					processNext = true;
				} else if (state == MessageQueue.DELETE) {
					it.remove();
				}
			}

			// If any of chats are in state WAIT_SIG or GET_MESSAGE, request another
			// iteration
			if (processNext)
				mSendRequested.set(true);

			// Second step - find oldest waiting queue and peek it's message
			MessageQueue sendQueue = null;
			long oldestPutTime = Long.MAX_VALUE;
			for (int i = 0; i < mSendQueues.size(); i++) {
				MessageQueue queue = mSendQueues.get(i);
				long putTime = queue.getPutTime();
				if (putTime < oldestPutTime) {
					oldestPutTime = putTime;
					sendQueue = queue;
				}
			}
			if (sendQueue == null) // Possible if on first step wasn't found any
									// chats in state GET_MESSAGE
				return;

			// Invoke the send callback. ChatId is passed for possible
			// additional processing
			sendMessageCallback(sendQueue.getChatId(), sendQueue.getMessage(currentTime));
		}
	}

	private static class MessageQueue {
		public static final int EMPTY = 0; // Queue is empty
		public static final int WAIT_SIG = 1; // Queue has message(s) but not yet
											// allowed to send
		public static final int DELETE = 2; // No one message of given queue was
											// sent longer than
											// CHAT_INACTIVE_INTERVAL, delete
											// for optimisation
		public static final int GET_MESSAGE = 3; // Queue has message(s) and
													// ready to send
		private final ConcurrentLinkedQueue<BotApiMethod<? extends Serializable>> mQueue = new ConcurrentLinkedQueue<>();
		private final Long mChatId;
		private long mLastSendTime; // Time of last peek from queue
		private volatile long mLastPutTime; // Time of last put into queue

		public MessageQueue(Long chatId) {
			mChatId = chatId;
		}

		public synchronized void putMessage(BotApiMethod<? extends Serializable> msg) {
			mQueue.add(msg);
			mLastPutTime = System.currentTimeMillis();
		}

		public synchronized int getCurrentState(long currentTime) {
			// currentTime is passed as parameter for optimisation to do not
			// recall currentTimeMillis() many times
			long interval = currentTime - mLastSendTime;
			boolean empty = mQueue.isEmpty();
			if (!empty && interval > ONE_CHAT_SEND_INTERVAL)
				return GET_MESSAGE;
			else if (interval > CHAT_INACTIVE_INTERVAL)
				return DELETE;
			else if (empty)
				return EMPTY;
			else
				return WAIT_SIG;
		}

		public synchronized BotApiMethod<? extends Serializable> getMessage(long currentTime) {
			mLastSendTime = currentTime;
			return mQueue.poll();
		}

		public long getPutTime() {
			return mLastPutTime;
		}

		public Long getChatId() {
			return mChatId;
		}
	}

	// Something like destructor
	/* (non-Javadoc)
	 * @see pogorobot.telegram.bot.TelegramBot#finish()
	 */
	@Override
	public void finish() {
		mSendTimer.cancel();
	}

	// This method must be called instead of all calls to sendMessage(),
	// editMessageText(), sendChatAction() etc...
	// for performing time-based sends obeying the basic Telegram limits (no
	// more 30 msgs per second in different chats,
	// no more 1 msg per second in any single chat). The method can be safely
	// called from multiple threads.
	// Order of sends to any given chat is guaranteed to remain the same as
	// order of calls. Sends to different chats can be out-of-order depending on
	// timing.
	// Example of call:
	/* (non-Javadoc)
	 * @see pogorobot.telegram.bot.TelegramBot#sendTimed(java.lang.Long, org.telegram.telegrambots.api.methods.BotApiMethod)
	 */
	@Override
	public void sendTimed(Long chatId, BotApiMethod<? extends Serializable> messageRequest) {
		MessageQueue queue = mMessagesMap.get(chatId);
		if (queue == null) {
			queue = new MessageQueue(chatId);
			queue.putMessage(messageRequest);
			mMessagesMap.put(chatId, queue);
		} else {
			queue.putMessage(messageRequest);
			MessageQueue absent = mMessagesMap.putIfAbsent(chatId, queue); // Double check, because
														// the queue can be
														// removed from hashmap
														// on state DELETE
			if (absent == null) {
				logger.warn("no mapping of chat " + chatId + " , now queue state is "
						+ queue.getCurrentState(System.currentTimeMillis()));
			}
		}
		mSendRequested.set(true);
	}

	// When time of actual send comes this callback is called with the same
	// parameters as in call to sendTimed().
	// @Override
	/* (non-Javadoc)
	 * @see pogorobot.telegram.bot.TelegramBot#sendMessageCallback(java.lang.Long, org.telegram.telegrambots.api.methods.BotApiMethod)
	 */
	@Override
	public void sendMessageCallback(Long chatId, BotApiMethod<? extends Serializable> messageRequest) {
		try {
			execute(messageRequest);
		} catch (TelegramApiException e) {
			logger.error(e.getMessage(), e);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}
	// */

	@Override
	public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method)
			throws TelegramApiException {
		try {
			return super.execute(method);
		} catch (TelegramApiException e) {
			if (e instanceof TelegramApiRequestException) {
				TelegramApiRequestException x = (TelegramApiRequestException) e;
				logger.error(
						x.getErrorCode() + " - " + x.getApiResponse()
								+ (x.getParameters() != null ? " - Parameter: " + x.getParameters().toString() : ""),
						x.getCause());
			} else if (e instanceof TelegramApiValidationException) {
				TelegramApiValidationException x = (TelegramApiValidationException) e;
				logger.error(x.getMethod() + " - " + x.getObject() + " - Error: " + x.toString(), x.getCause());
			}
			throw e;
		} catch (Exception e) {
			logger.error(e.getMessage(), e.getCause());
			if (e instanceof TelegramApiException) {
				throw (TelegramApiException) e;
			}
		}
		if (method == null) {
			throw new TelegramApiException("Parameter method can not be null");
		}
		return sendApiMethod(method);
	}

	/**
	 * Start PogoBot
	 * 
	 * @param options
	 *            telegramOptions
	 * @param allowCommandsWithUsername
	 *            true if allowed with user name
	 * @param botUsername
	 *            name of the bot
	 */
	public PogoBot(DefaultBotOptions options, boolean allowCommandsWithUsername, String botUsername) {
		super(options, true, botUsername);
		mSendTimer.schedule(new MessageSenderTask(), MANY_CHATS_SEND_INTERVAL, MANY_CHATS_SEND_INTERVAL);
		registerDefaultAction((absSender, message) -> {
			SendMessage commandUnknownMessage = new SendMessage();
			commandUnknownMessage.setChatId(message.getChatId());
			StringBuilder messageBuilder = new StringBuilder();
			messageBuilder.append("Das Command '" + message.getText() + "' ist dem Bot unbekannt. Hier kommt Hilfe "
					+ Emoji.AMBULANCE + "\n");

			messageBuilder.append("Das sind die Befehle des Bot:\n\n");

			for (IBotCommand botCommand : this.getRegisteredCommands()) {
				messageBuilder.append(botCommand.toString()).append("\n\n");
			}
			commandUnknownMessage.setText(messageBuilder.toString());
			commandUnknownMessage.enableHtml(true);
			try {
				Message sentMessage = absSender.execute(commandUnknownMessage);
				logger.info(sentMessage.toString());
			} catch (TelegramApiException e) {
				logger.error(e.getMessage(), e);
			}
		});
	}

	/**
	 * 
	 * @param botUsername
	 *            name of the bot
	 * @param bottoken
	 *            token from botfather
	 */
	public PogoBot(String botUsername, String bottoken) {
		this(ApiContext.getInstance(DefaultBotOptions.class), botUsername);
		this.bottoken = bottoken;
	}

	/**
	 * 
	 * @param options
	 *            telegram options
	 * @param botUsername
	 *            name of the bot
	 */
	public PogoBot(DefaultBotOptions options, String botUsername) {
		this(options, true, botUsername);
	}

	public static Configuration getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Configuration configuration) {
		PogoBot.configuration = configuration;
	}


	/* (non-Javadoc)
	 * @see pogorobot.telegram.bot.TelegramBot#processNonCommandUpdate(org.telegram.telegrambots.api.objects.Update)
	 */
	@Override
	public void processNonCommandUpdate(Update update) {
		// if (update.hasInlineQuery()) {
		// handleIncomingInlineQuery(update.getInlineQuery());
		// } else
		if (update.hasCallbackQuery()) {
			CallbackQuery callbackQuery = update.getCallbackQuery();
			updateUser(callbackQuery.getFrom());
			handleCallbackQuery(update);
			// if (message != null) {
			// logger.info(message.toString());
			// }
			// return message;
		}
		if (update.hasInlineQuery()) {
			InlineQuery inlineQuery = update.getInlineQuery();
			updateUser(inlineQuery.getFrom());
			handleInlineQuery(inlineQuery);
		}
		if (update.hasEditedMessage()) {
			handleEditedMessage(update.getEditedMessage());
		}
		if (update.hasMessage()) {
			Message message = update.getMessage();
			updateUser(message.getFrom());
			Message handleUserMessage = null;
			if (message.hasPhoto()) {
				Integer highestFilesize = null;
				String fileId = null;
				for (PhotoSize photo : message.getPhoto()) {
					Integer fileSize = photo.getFileSize();
					if (highestFilesize == null || fileSize > highestFilesize) {
						fileId = photo.getFileId();
						highestFilesize = fileSize;
					}
				}
				GetFile getFile = new GetFile();
				getFile.setFileId(fileId);
				File image = executeBotApiMethod(getFile);
				String url = "https://api.telegram.org/file/bot" + getBotToken() + "/" + image.getFilePath();
				raidImageScanner.scanImage(url);
			} else if (message.hasDocument()) {
				Document document = message.getDocument();
				String fileId = document.getFileId();
				GetFile getFile = new GetFile();
				getFile.setFileId(fileId);
				File image = executeBotApiMethod(getFile);
				String url = "https://api.telegram.org/file/bot" + getBotToken() + "/" + image.getFilePath();
				raidImageScanner.scanImage(url);
			}
			if (message.isUserMessage()) {
				handleUserMessage = handleUserMessage(message);
			} else if (message.hasText()) {
				handleUserMessage = handleMessageText(message);
			}
			if (null != handleUserMessage) {
				logger.info(handleUserMessage.toString());
			}
		}
	}

	private void updateUser(org.telegram.telegrambots.meta.api.objects.User from) {
		User user;
		if (userService != null) {
			user = userService.getOrCreateUser(from.getId().toString());
			user.setTelegramName(from.getUserName());
			String prename = from.getFirstName() != null ? from.getFirstName() + " " : "";
			String surname = from.getLastName() != null ? from.getLastName() : "";
			user.setName(prename + surname);
			user.setTelegramId(from.getId().toString());
			user.setTelegramActive(true);
			user = userService.updateOrInsertUser(user);
		}
	}

	private void handleEditedMessage(Message editedMessage) {
		org.telegram.telegrambots.meta.api.objects.User from = editedMessage.getFrom();
		User user = userService.getOrCreateUser(from.getId().toString());
		if (userService == null || !userService.getOrCreateUser(from.getId().toString()).isTelegramActive()) {
			return;
		}
		if (editedMessage.hasLocation()) {
			logger.info("New location for " + user.getId() + "...");
			Location location = editedMessage.getLocation();
			user = setLocationForUser(user, location);
		} else if (editedMessage.hasPhoto()) {
			editedMessage.getPhoto().stream().forEach((photo) -> {
				String filePath = photo.getFilePath();
			});
		}
		logger.info(editedMessage.toString());
	}

	private void handleInlineQuery(InlineQuery inlineQuery) {
		org.telegram.telegrambots.meta.api.objects.User from = inlineQuery.getFrom();
		User user = null;
		if (userService == null || !userService.getOrCreateUser(from.getId().toString()).isTelegramActive()) {
			return;
		}
		if (inlineQuery.hasLocation()) {
			user = userService.getOrCreateUser(from.getId().toString());
			logger.info("New inlined location for " + from.getId() + "...");
			Location location = inlineQuery.getLocation();
			user = setLocationForUser(user, location);
		}
		if (inlineQuery.hasQuery()) {
			String query = inlineQuery.getQuery();
			System.out.println(query);
		}
		Boolean method = sendAnswerCallbackQuery(inlineQuery);
		logger.info(method.toString());
	}

	private Message handleMessageText(Message message) {
		if (message.getChat().isChannelChat() || message.getChat().isGroupChat() || message.getChat().isSuperGroupChat()
				|| message.isChannelMessage() || message.isGroupMessage() || message.isSuperGroupMessage()) {
			return null;
		}
		SendMessage echoMessage = new SendMessage();
		echoMessage.setChatId(message.getChatId());
		echoMessage.setText("Hey, deine Nachricht:\n" + message.getText());
		echoMessage.disableNotification();
		return executeBotApiMethod(echoMessage);
	}

	private Message handleUserMessage(Message message) {

		SendMessage echoMessage = telegramHandlerService.answerUserMessage(message, message.getFrom());
		return executeBotApiMethod(echoMessage);
	}

	private BotApiMethod<? extends Serializable> handleCallbackQuery(Update update) {
		CallbackQuery callbackquery = update.getCallbackQuery();
		String[] data = callbackquery.getData().split(" ");
		String callbackCommand = data[0];

		logger.info(callbackquery.getData());
		// if (1 == 0) {
		// Boolean message = this
		// .sendAnswerCallbackQuery("Bitte einen der Buttons verwenden", false,
		// callbackquery);
		// return null;
		// }

		// Following callbackCommands are for new raid/egg input manually done by users

		if (callbackCommand.equals(TelegramKeyboardService.NEWRAIDATGYMCONFIRMED)) {
			handleSendNewRaid(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.BEGINNEWRAIDOREGG)) {
			handleCreateRaid(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.NEWRAIDOREGG)) {
			handlePokemonChoiceRaidOrEgg(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.NEWRAIDATGYM)) {
			handleCreateRaidAtGym(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.TIMEFORNEWRAIDATGYMMINUTE)) {
			handleCreateRaidAtGymGetExactTime(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.DATEFORNEWRAIDATGYM)) {
			handleCreateRaidAtGymGetDate(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.TIMEFORNEWRAIDATGYMMINUTES)) {
			handleCreateRaidAtGymGetTimeMinutes(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.TIMEFORNEWRAIDATGYM)) {
			handleCreateRaidAtGymGetTimeHours(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.CONFIRMNEWRAIDATGYM)) {
			handleConfirmRaidAtGym(callbackquery, data);
		}
		if (callbackCommand.equals(TelegramKeyboardService.SIGNUPRAID)) {
			handleSignupRaidEvent(callbackquery, data);
		} else

		// Adding/removing personal pokemon to get

		if (callbackCommand.equals(TelegramKeyboardService.ADD)) {
			handlePokemonAdd(callbackquery, data, false);
		} else if (callbackCommand.equals(TelegramKeyboardService.REMOVE)) {
			handlePokemonRemove(callbackquery, data, false);
		} else

		// Adding/removing personal raidpokemon to get and set level

		if (callbackCommand.equals(TelegramKeyboardService.RAIDPOKEMONADD)) {
			handlePokemonAdd(callbackquery, data, true);
		} else if (callbackCommand.equals(TelegramKeyboardService.RAIDPOKEMONREMOVE)) {
			handlePokemonRemove(callbackquery, data, true);
		} else if (callbackCommand.equals(TelegramKeyboardService.RAIDLEVEL)) {
			handleRaidLevel(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.MINIVSELECT)) {
			handleIvSelect(callbackquery, data);
		} else if (callbackCommand.equals(TelegramKeyboardService.RAIDPOKEMON)) {
			handleRaidPokemon(callbackquery, data);
		} else

		// Enable/disable all raids in personal bot

		if (callbackCommand.equals("enableraids")) {
			handleRaidEnable(callbackquery, data);
		} else if (callbackCommand.equals("disableraids")) {
			handleRaidDisable(callbackquery, data);
		} else

		// Handling distance/area based messages
		if (callbackCommand.equals(TelegramKeyboardService.DISTANCESELECT)) {
			handleSettingsDistanceselect(callbackquery, data);
		}
		else if (callbackCommand.equals(TelegramKeyboardService.ADDAREA)) {
			handleSettingsLocationarea(callbackquery, data, Type.POKEMON);
		} else if (callbackCommand.equals(TelegramKeyboardService.ADDRAIDAREA)) {
			handleSettingsLocationarea(callbackquery, data, Type.RAID);
		} else if (callbackCommand.equals(TelegramKeyboardService.ADDIVAREA)) {
			handleSettingsLocationarea(callbackquery, data, Type.IV);
		} else if (callbackCommand.equals(TelegramKeyboardService.REMOVEAREA)) {
			handleSettingsLocationarea(callbackquery, data, Type.POKEMON);
		} else if (callbackCommand.equals(TelegramKeyboardService.REMOVERAIDAREA)) {
			handleSettingsLocationarea(callbackquery, data, Type.RAID);
		} else if (callbackCommand.equals(TelegramKeyboardService.REMOVEIVAREA)) {
			handleSettingsLocationarea(callbackquery, data, Type.IV);
			// } else if (callbackCommand.equals("disableraids")) {
			// handleRaidDisable(callbackquery, data);
			// } else if (callbackCommand.equals("disableraids")) {
			// handleRaidDisable(callbackquery, data);
		} else {
			Boolean message = this
					.sendAnswerCallbackQuery("Bitte einen der Buttons verwenden", false, callbackquery);
			return null;
		}
		this.sendAnswerCallbackQuery("Eingabe wurde bearbeitet", false, callbackquery);
		return null;
	}

	private void handleSignupRaidEvent(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getSignupRaidDialog(callbackquery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleConfirmRaidAtGym(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getConfirmNewRaidDialog(callbackquery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleCreateRaidAtGymGetDate(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getDateForNewRaidDialog(callbackquery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleCreateRaidAtGym(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getChooseRaidLevelOrPokemonDialog(callbackquery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleCreateRaidAtGymGetTimeHours(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getHoursForNewRaidDialog(callbackquery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleCreateRaidAtGymGetTimeMinutes(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getMinutesForNewRaidDialog(callbackquery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleCreateRaidAtGymGetExactTime(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getExactTimeForNewRaidDialog(callbackquery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleRaidPokemon(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getRaidPokemonDialog(callbackquery);
		executeBotApiMethod(editMessage);
	}

	private void handleRaidLevel(CallbackQuery callbackquery, String[] data) {
		EditMessageText message = telegramHandlerService.getRaidLevelDialog(callbackquery, data);
		if (message == null) {
			return;
		}
		executeBotApiMethod(message);
	}

	private void handleIvSelect(CallbackQuery callbackquery, String[] data) {
		BotApiMethod<? extends Serializable> message = telegramHandlerService.getIvSettingDialog(callbackquery, data);
		if (message == null) {
			return;
		}
		executeBotApiMethod(message);
	}

	private void handleSettingsDistanceselect(CallbackQuery callbackquery, String[] data) {
		EditMessageText message = telegramHandlerService.getDistanceSelectDialog(callbackquery, data);
		if (null == message) {
			return;
		}
		executeBotApiMethod(message);
	}

	private void handleRaidDisable(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getRaidDisabledDialog(callbackquery);
		executeBotApiMethod(editMessage);
	}

	private void handleRaidEnable(CallbackQuery callbackquery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getEnabledRaidsMainDialog(callbackquery);
		executeBotApiMethod(editMessage);
	}

	private void handlePokemonRemove(CallbackQuery callbackquery, String[] data, boolean raids) {
		EditMessageText editMessage = telegramHandlerService.getPokemonRemoveDialog(callbackquery, data, raids);
		executeBotApiMethod(editMessage);
	}

	private void handleCreateRaid(CallbackQuery callbackQuery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getChooseRaidOrEggDialog(callbackQuery, data);
		executeBotApiMethod(editMessage);
	}

	private void handleSendNewRaid(CallbackQuery callbackQuery, String[] data) {
		// TODO: Send new Raid-Message to channels and people
		// IncomingManualRaid message = new IncomingManualRaid();
		// message.
		IncomingManualRaid manualRaid = telegramHandlerService.getManualRaid(callbackQuery, data);
		messageContentProcessor.processContent(manualRaid);
		
		String share = data.length > 6 ? data[6] : "";
		
		// chat
		// callbackQuery.getMessage().

		if ("share".equals(share)) {
			// TODO: activate!
			System.out.println("Share the raid!");
			// messageContentProcessor.shareRaid(manualRaid);
		}
		
		// EditMessageText editMessage =
		// telegramHandlerService.getChooseShareRaidDialog(callbackQuery, data,
		// manualRaid);
		// executeBotApiMethod(editMessage);
	}

	private Message handlePokemonChoiceRaidOrEgg(CallbackQuery callbackQuery, String[] data) {
		EditMessageText editMessage = telegramHandlerService.getChooseGymOfRaidChoiceDialog(callbackQuery, data);
		Serializable method = executeBotApiMethod(editMessage);
		if (method instanceof Message) {
			Message message = (Message) method;
			return message;
		}
		logger.warn("No response from handlePokemonChoiceRaidOrEgg");
		return null;
	}
	// private void handleSettingsLocation(CallbackQuery callbackquery, String[]
	// data) {
	// SendMessage message =
	// telegramHandlerService.getLiveLocationDialog(callbackquery.getFrom().getId().toString(),
	// data);
	// if (null == message) {
	// return;
	// }
	// executeBotApiMethod(message);
	// }

	private <T extends Serializable, Method extends BotApiMethod<T>> T executeBotApiMethod(Method message) {
		T result = null;
		try {
			result = execute(message);

		} catch (TelegramApiException e) {
			if (e instanceof TelegramApiRequestException) {
				TelegramApiRequestException x = (TelegramApiRequestException) e;
				logger.error(
						x.getErrorCode() + " - " + x.getApiResponse() + (x.getParameters() != null
								? " - Parameter: " + x.getParameters().toString()
								: ""),
						x.getCause());
			} else if (e instanceof TelegramApiValidationException) {
				TelegramApiValidationException x = (TelegramApiValidationException) e;
				logger.error(x.getMethod() + " - " + x.getObject() + " - Error: " + x.toString(), x.getCause());
			} else {
				logger.error(e.getMessage(), e.getCause());
			}
		}
		return result;
	}

	private void handleSettingsLocationarea(CallbackQuery callbackquery, String[] data, Type pokemonFilter) {
		EditMessageText editMessage = telegramHandlerService.getLocationSettingsAreaDialog(callbackquery, data,
				pokemonFilter);
		executeBotApiMethod(editMessage);
	}

	private void handlePokemonAdd(CallbackQuery callbackquery, String[] data, boolean raids) {
		EditMessageText editMessage = telegramHandlerService.getPokemonAddDialog(callbackquery, data, raids);
		executeBotApiMethod(editMessage);
	}

	/**
	 * 
	 * @param text
	 *            The text that should be shown
	 * @param alert
	 *            If the text should be shown as a alert or not
	 * @param callbackquery
	 * @return
	 * @throws TelegramApiException
	 */
	private Boolean sendAnswerCallbackQuery(String text, boolean alert,
			CallbackQuery callbackquery) {
		AnswerCallbackQuery answerCallbackQuery = new AnswerCallbackQuery();
		answerCallbackQuery.setCallbackQueryId(callbackquery.getId());
		answerCallbackQuery.setShowAlert(alert);
		answerCallbackQuery.setText(text);
		Boolean answer = executeBotApiMethod(answerCallbackQuery);
		return answer;
	}

	private Boolean sendAnswerCallbackQuery(InlineQuery inlineQuery) {
		AnswerInlineQuery answerInlineQuery = new AnswerInlineQuery();
		answerInlineQuery.setInlineQueryId(inlineQuery.getId());
		answerInlineQuery.setSwitchPmParameter("personalMode");
		answerInlineQuery.setPersonal(true);
		List<InlineQueryResult> results = new ArrayList<>();
		InlineQueryResultArticle queryResultArticle = new InlineQueryResultArticle();
		queryResultArticle.setTitle("Privater Chat");
		queryResultArticle.setId(inlineQuery.getId());
		queryResultArticle.setDescription("hiermit beginnen wir eine Unterhaltung");
		InputTextMessageContent inputMessageContent = new InputTextMessageContent();
		inputMessageContent.setMessageText("/start");
		queryResultArticle.setInputMessageContent(inputMessageContent);
		results.add(queryResultArticle);
		answerInlineQuery.setResults(results);
		Boolean answer = executeBotApiMethod(answerInlineQuery);
		return answer;
	}

	/* (non-Javadoc)
	 * @see pogorobot.telegram.bot.TelegramBot#getBotToken()
	 */
	@Override
	public String getBotToken() {
		return bottoken;
	}

	private User setLocationForUser(User user, Location location) {
		Double latitude = location.getLatitude().doubleValue();
		Double longitude = location.getLongitude().doubleValue();
		user.getUserFilter().setLatitude(latitude);
		user.getUserFilter().setLongitude(longitude);
		user = userService.updateOrInsertUser(user);
		return user;
	}

	@Override
	protected boolean filter(Message message) {
		return message.getChat().isGroupChat();
	}
}