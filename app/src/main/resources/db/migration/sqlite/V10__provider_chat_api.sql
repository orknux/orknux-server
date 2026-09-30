-- Which OpenAI API an Azure OpenAI provider's chats are sent through; existing
-- Azure providers move onto Responses. The postgres copy, V332, carries the
-- reasoning.

ALTER TABLE model_provider ADD COLUMN chat_api varchar(24);

UPDATE model_provider SET chat_api = 'RESPONSES' WHERE type = 'AZURE_OPENAI';
