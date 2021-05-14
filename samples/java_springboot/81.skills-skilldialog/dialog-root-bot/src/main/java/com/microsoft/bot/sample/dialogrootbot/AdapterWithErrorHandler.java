// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MT License.

package com.microsoft.bot.sample.dialogrootbot;

import java.util.concurrent.CompletableFuture;

import com.microsoft.bot.builder.ConversationState;
import com.microsoft.bot.builder.MessageFactory;
import com.microsoft.bot.builder.OnTurnErrorHandler;
import com.microsoft.bot.builder.StatePropertyAccessor;
import com.microsoft.bot.builder.TurnContext;
import com.microsoft.bot.builder.skills.BotFrameworkSkill;
import com.microsoft.bot.connector.Async;
import com.microsoft.bot.connector.authentication.MicrosoftAppCredentials;
import com.microsoft.bot.integration.BotFrameworkHttpAdapter;
import com.microsoft.bot.integration.Configuration;
import com.microsoft.bot.integration.SkillHttpClient;
import com.microsoft.bot.sample.dialogrootbot.dialogs.MainDialog;
import com.microsoft.bot.sample.dialogrootbot.middleware.ConsoleLogger;
import com.microsoft.bot.sample.dialogrootbot.middleware.LoggerMiddleware;
import com.microsoft.bot.schema.Activity;
import com.microsoft.bot.schema.EndOfConversationCodes;
import com.microsoft.bot.schema.InputHints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AdapterWithErrorHandler extends BotFrameworkHttpAdapter {

    private ConversationState conversationState = null;
    private SkillHttpClient skillHttpClient = null;
    private SkillsConfiguration skillsConfiguration = null;
    private Configuration configuration = null;
    private Logger logger = LoggerFactory.getLogger(AdapterWithErrorHandler.class);

    public AdapterWithErrorHandler(
        Configuration configuration,
        ConversationState conversationState,
        SkillHttpClient skillHttpClient,
        SkillsConfiguration skillsConfiguration
    ) {
        super(configuration);
        this.configuration = configuration;
        this.conversationState = conversationState;
        this.skillHttpClient = skillHttpClient;
        this.skillsConfiguration = skillsConfiguration;
        setOnTurnError(new AdapterErrorHandler());
        use(new LoggerMiddleware(new ConsoleLogger()));
    }

    private class AdapterErrorHandler implements OnTurnErrorHandler {

        @Override
        public CompletableFuture<Void> invoke(TurnContext turnContext, Throwable exception) {
            return sendErrorMessage(turnContext, exception).thenAccept(result -> {
                endSkillConversation(turnContext);
            }).thenAccept(endResult -> {
                clearConversationState(turnContext);
            });
        }

        private CompletableFuture<Void> sendErrorMessage(TurnContext turnContext, Throwable exception) {
            try {
                // Send a message to the user.
                String errorMessageText = "The bot encountered an error or bug.";
                Activity errorMessage =
                    MessageFactory.text(errorMessageText, errorMessageText, InputHints.IGNORING_INPUT);
                return turnContext.sendActivity(errorMessage).thenAccept(result -> {
                    String secondLineMessageText = "To continue to run this bot, please fix the bot source code.";
                    Activity secondErrorMessage =
                        MessageFactory.text(secondLineMessageText, secondLineMessageText, InputHints.EXPECTING_INPUT);
                    turnContext.sendActivity(secondErrorMessage)
                        .thenApply(
                            sendResult -> {
                                // Send a trace activity, which will be displayed in the Bot Framework Emulator.
                                // Note: we return the entire exception in the value property to help the
                                // developer;
                                // this should not be done in production.
                                return TurnContext.traceActivity(
                                    turnContext,
                                    String.format("OnTurnError Trace %s", exception.getMessage())
                                );
                            }
                        );
                }).thenApply(finalResult -> null);

            } catch (Exception ex) {
                logger.error("Exception caught in sendErrorMessage", ex);
                return Async.completeExceptionally(ex);
            }
        }

        private CompletableFuture<Void> endSkillConversation(TurnContext turnContext) {
            if (skillHttpClient == null || skillsConfiguration == null) {
                return CompletableFuture.completedFuture(null);
            }

            // Inform the active skill that the conversation is ended so that it has a chance to clean up.
            // Note: the root bot manages the ActiveSkillPropertyName, which has a value while the root bot
            // has an active conversation with a skill.
            StatePropertyAccessor<BotFrameworkSkill> skillAccessor =
                conversationState.createProperty(MainDialog.ActiveSkillPropertyName);
            // forwarded to a Skill.
            return skillAccessor.get(turnContext, () -> null).thenApply(activeSkill -> {
                if (activeSkill != null) {
                    String botId = configuration.getProperty(MicrosoftAppCredentials.MICROSOFTAPPID);

                    Activity endOfConversation = Activity.createEndOfConversationActivity();
                    endOfConversation.setCode(EndOfConversationCodes.ROOT_SKILL_ERROR);
                    endOfConversation
                        .applyConversationReference(turnContext.getActivity().getConversationReference(), true);

                    return conversationState.saveChanges(turnContext, true).thenCompose(saveResult -> {
                        return skillHttpClient.postActivity(
                            botId,
                            activeSkill,
                            skillsConfiguration.getSkillHostEndpoint(),
                            endOfConversation,
                            Object.class
                        );

                    });
                }
                return CompletableFuture.completedFuture(null);
            }).thenApply(result -> null);
        }

        private CompletableFuture<Void> clearConversationState(TurnContext turnContext) {
            try {
                return conversationState.delete(turnContext);
            } catch (Exception ex) {
                return Async.completeExceptionally(ex);
            }
        }

    }
}
