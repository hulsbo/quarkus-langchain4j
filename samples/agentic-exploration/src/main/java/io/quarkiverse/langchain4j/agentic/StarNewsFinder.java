package io.quarkiverse.langchain4j.agentic;

import static dev.langchain4j.model.chat.request.ResponseFormatType.JSON;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.output.ServiceOutputParser;
import dev.langchain4j.web.search.WebSearchTool;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.ToolBox;
import io.quarkiverse.langchain4j.runtime.LangChain4jUtil;
import io.quarkiverse.langchain4j.runtime.QuarkusServiceOutputParser;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestQuery;
import picocli.CommandLine;

@CommandLine.Command(name = "star", description = "Gets news star news about the person")
public class StarNewsFinder implements Runnable {

    private static final ServiceOutputParser SERVICE_OUTPUT_PARSER = new QuarkusServiceOutputParser();

    @CommandLine.Parameters(description = "input", defaultValue = "My name is George and my astrological sign is "
                                                                  + "Aquarius, can you give me some news?")
    private String input;

    private final AstrologyNewsAgent astrologyNewsAgent;
    private final HoroscopeService horoscopeService;
    private final ChatModel chatModel;

    private final Map<String, AgentSpecification> agentsSpecs = Map.of("astrologyAgent", new AgentSpecification("astrologyAgent", "An agent that specializes in finding astrology related news"));

    public StarNewsFinder(AstrologyNewsAgent astrologyNewsAgent, HoroscopeService horoscopeService,
                          ChatModel chatModel) {
        this.astrologyNewsAgent = astrologyNewsAgent;
        this.horoscopeService = horoscopeService;
        this.chatModel = chatModel;
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    @Override
    public void run() {
        String agentsList = agentsSpecs.values()
                .stream()
                .map(agentSpec -> "{" + agentSpec.name() + ": " + agentSpec.description() + "}")
                .collect(Collectors.joining(", "));

        var exitLoopTool = ToolSpecification.builder()
                .description("Exits agent invocation when nothing else needs to be done").name("exitLoop")
                .build();

        var plannerChatModel = chatModel;

        while (true) {
            ChatResponse chatResponse = determineAgentToUse(plannerChatModel, input, agentsList);
            if (chatResponse.aiMessage().hasToolExecutionRequests()) {
                if (chatResponse.aiMessage().toolExecutionRequests().stream().allMatch(tr -> tr.name().equals("exitLoop"))) {
                    break;
                }
                Set<String> unknownTools = chatResponse.aiMessage().toolExecutionRequests().stream().map(
                        ToolExecutionRequest::name).filter(name -> !name.equals("exitLoop")).collect(
                        Collectors.toSet());
                throw new UnknownToolException("Unknown tool execution requests: " + String.join(",", unknownTools));
            } else {
                AgentSpecification agentSpecification =
                        (AgentSpecification) SERVICE_OUTPUT_PARSER.parse(chatResponse, AgentSpecification.class);
                if ("unknown".equals(agentSpecification.name())) {
                    throw new UnknownAgentException();
                } else {

                    System.out.println("Need to execute '" + agentSpecification + "'");

                    // TODO: this is hard-coded and needs to be replaced with an abstraction
                    if ("astrologyAgent".equals(agentSpecification.name())) {

                        //TODO: we need to obtain the parameters from the state or resolve them somehow
                        // the easiest ones to resolve are the ones for which we just throw the conversation to an LLM
                        // and it can extract the info
                        // For others, we might need to have a DAG that will allow us to resolve each parameter

                        Person person = useAgentToExtractParameter(input, SERVICE_OUTPUT_PARSER.jsonSchema(Person.class).get(), Person.class);
                        // Horoscope (arc resolver) -> sign (llm resolver)
                        Horoscope horoscope = useAgentToExtractParameter(input, SERVICE_OUTPUT_PARSER.jsonSchema(Horoscope.class).get(), Horoscope.class);

                        RelevantNewsStories relevantNewsStories = astrologyNewsAgent.find(person, horoscope);
                        System.out.println(relevantNewsStories);

                    } else {
                        throw new IllegalStateException("need to figure this out");
                    }
                    break;
                }
            }
        }
    }

    @RegisterAiService
    @ApplicationScoped
    public interface AstrologyNewsAgent {

        @dev.langchain4j.service.SystemMessage("You are an agent that specializes in finding astrology related news")
        @dev.langchain4j.service.UserMessage(
                """
                        {person.name} is an astrology believer with the sign {person.sign}.
                        Their horoscope for today is:
                            <horoscope>{horoscope.summary}</horoscope>
                        Given this, use web tools and generate search queries
                        to find 3 relevant news stories summarize them in a few sentences.
                        Include the URL for each story.
                        Do not look for another horoscope reading or return results directly about astrology;
                        find stories relevant to the reading above.
                        
                        For example:
                        - If the horoscope says that they may
                        want to work on relationships, you could find news stories about
                        novel gifts
                        - If the horoscope says that they may want to work on their career,
                        find news stories about training courses.
                        """)
        @ToolBox({WebSearchTool.class})
        RelevantNewsStories find(Person person, Horoscope horoscope);
    }

    @Singleton
    public static class HoroscopeService {

        private final HoroscopeClient horoscopeClient;

        public HoroscopeService(@RestClient HoroscopeClient horoscopeService) {
            this.horoscopeClient = horoscopeService;
        }

//        @OutputKey("horoscope")
//        public Horoscope dailyHoroscope(@InputKey String sign) {
        public Horoscope dailyHoroscope(String sign) {
            return new Horoscope(horoscopeClient.horoscope(sign).data().horoscopeData());
        }
    }

    @RegisterRestClient(baseUri = "https://horoscope-app-api.vercel.app")
    public interface HoroscopeClient {

        @GET
        @Path("/api/v1/get-horoscope/daily")
        HoroscopeResponse horoscope(@RestQuery String sign);
    }

    public record HoroscopeResponse(boolean success, int status, HoroscopeData data) {

    }

    private record HoroscopeData(String date, @JsonProperty("horoscope_data") String horoscopeData) {

    }


    public record NewsStory(String url, String title, String summary) {

    }

    public record RelevantNewsStories(List<NewsStory> items) {

    }

    public record Person(String name, String sign) {

    }

    public record Horoscope(String summary) {
    }

    /**********************************************

     * The stuff below is what can be generalized *

     * ********************************************/

    private static ChatResponse determineAgentToUse(ChatModel plannerChatModel, String input, String agentsList) {
        ChatRequest plannerChatRequest = ChatRequest.builder()
                .messages(
                        SystemMessage.systemMessage(
                                String.format("""
                                        You are a planner expert that is provided with a set of agents.
                                        You know nothing about any domain, don't take any assumptions about the user request,
                                        the only thing that you can do is relying on the provided agents.
                                        
                                        Your role is to analyze the user request and decide which of the provided agent to call
                                        next to address it. You return an agent invocation containing the name of the agent.
                                        Generate the agent invocation also considering the past messages.
                                        
                                        For each agent it will be provided both the name and description in the format {name: description}.
                                        If none of agents match, return exactly {"name": "unknown"}.
                                        
                                        Decide which agent to invoke next, doing things in small steps and never taking any shortcuts or relying on your own knowledge.
                                        Ignore the fact the user's request is already clear or explicit.
                                        Don't try to answer the user request on any intermediary doubt on your own.
                                        You know nothing except the provided agents and their descriptions.
                                        You MUST query all necessary agents.
                                        
                                        The comma separated list of available agents is: '%s'
                                        """, agentsList)),
                        UserMessage.from(input)
                )
                .build();

        return plannerChatModel.chat(plannerChatRequest);
    }


    @SuppressWarnings("unchecked")
    private <T> T useAgentToExtractParameter(String input, JsonSchema schema, Class clazz) {
        AgentParameterResolver.OutputResolution outputResolution =
                AgentParameterResolver.execute(new AgentParameterResolver.ParameterContext() {
                    @Override
                    public Class clazz() {
                        return clazz;
                    }

                    @Override
                    public JsonSchema schema() {
                        return schema;
                    }

                    @Override
                    public List<ChatMessage> messages() {
                        return List.of(UserMessage.from(input));
                    }
                });
        if (outputResolution instanceof AgentParameterResolver.ResolutionDone d) {
            return (T) d.resolvedValue();
        } else {
            throw new IllegalStateException("Need to figure this out");
        }
    }

    /**
     * General interface used for resolving parameters of an agent
     */
    public interface AgentParameterResolver {

        boolean supports(ParameterContext parameterContext);

        OutputResolution resolve(ParameterContext parameterContext);


        static OutputResolution execute(ParameterContext parameterContext) {
            for (AgentParameterResolver resolver : allResolvers()) {
                if (resolver.supports(parameterContext)) {
                    return resolver.resolve(parameterContext);
                }
            }
            return new Unknown();
        }

        // TODO: generalize
        private static List<AgentParameterResolver> allResolvers() {
            ToolSpecification askForInputTool = ToolSpecification.builder()
                    .name("askForInput")
                    .description("Asks the user provide additional input that is needed by some part of the agent")
                    .parameters(JsonObjectSchema.builder().addStringProperty("input").required("input").build())
                    .build();
            OpenAiChatModel extractorChatModel = OpenAiChatModel.builder()
                    .modelName("gpt-4o")
                    .apiKey(ConfigProvider.getConfig().getValue("quarkus.langchain4j.openai.api-key", String.class))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            return List.of(
                    new HoroscopeParameterResolver(),
                    new LlmParameterProvider(extractorChatModel,askForInputTool));
        }

        interface ParameterContext {
            Class clazz();

            JsonSchema schema();

            List<ChatMessage> messages();
        }

        sealed interface OutputResolution permits ResolutionDone, ToolCallRequired, Unknown {


        }

        record ResolutionDone(Object resolvedValue) implements OutputResolution {
        }

        record ToolCallRequired(ToolExecutionRequest toolExecutionRequest) implements OutputResolution {

        }

        record Unknown() implements OutputResolution {

        }
    }

    /**
     * Specific implementation for the {@link Horoscope} parameter
     * TODO: this needs to be autogenerated
     */
    static class HoroscopeParameterResolver implements AgentParameterResolver {

        @Override
        public boolean supports(ParameterContext parameterContext) {
            return Horoscope.class.equals(parameterContext.clazz());
        }

        @Override
        public OutputResolution resolve(ParameterContext parameterContext) {
            HoroscopeService hs = Arc.container().instance(HoroscopeService.class).get();
            OutputResolution outputResolution = AgentParameterResolver.execute(new ParameterContext() {
                @Override
                public Class clazz() {
                    return String.class;
                }

                @Override
                public JsonSchema schema() {
                    return JsonSchema.builder()
                            .name("sign")
                            .rootElement(JsonObjectSchema.builder()
                                    .addStringProperty("value")
                                    .description("The astrological sign mentioned in the conversation")
                                    .required("value")
                                    .build())
                            .build();
                }

                @Override
                public List<ChatMessage> messages() {
                    return parameterContext.messages();
                }
            });
            if (outputResolution instanceof ResolutionDone d) {
                return new ResolutionDone(hs.dailyHoroscope((String) d.resolvedValue()));
            } else {
                throw new IllegalStateException("Need to figure this out");
            }
        }
    }

    /**
     * Implementation that uses an LLM to extract the necessary data from the conversation
     */
    static class LlmParameterProvider implements AgentParameterResolver {

        private static final String extractorSystemPrompt =
                """
                You are an agent that specializes in extracting structured information from a conversation.
                """;
        private static final String extractorUserPromptTemplate =
                """
                The conversion is the following:
                
                ---
                %s
                ---
                """;

        private final ChatModel chatModel;
        private final ToolSpecification askForInputTool;

        public LlmParameterProvider(ChatModel chatModel, ToolSpecification askForInputTool) {
            this.chatModel = chatModel;
            this.askForInputTool = askForInputTool;
        }

        @Override
        public boolean supports(ParameterContext parameterContext) {
            return true;
        }

        @Override
        public OutputResolution resolve(ParameterContext parameterContext) {
            StringBuilder sb = new StringBuilder();
            parameterContext.messages().forEach(cm -> {
                sb.append("Type: ").append(cm.type()).append("\n").append(LangChain4jUtil.chatMessageToText(cm));
            });
            String input = sb.toString();

            ChatResponse extractorChatResponse = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.systemMessage(extractorSystemPrompt),
                            UserMessage.from(String.format(extractorUserPromptTemplate, input)))
                    .parameters(
                            ChatRequestParameters.builder()
                                    .toolSpecifications(askForInputTool)
                                    .responseFormat(ResponseFormat.builder()
                                            .type(JSON)
                                            .jsonSchema(parameterContext.schema())
                                            .build())
                                    .build())
                    .build());
            if (extractorChatResponse.aiMessage().hasToolExecutionRequests()) {
                return new ToolCallRequired(extractorChatResponse.aiMessage().toolExecutionRequests().get(0));
            } else {
                Object result;
                if (String.class.equals(parameterContext.clazz())) {
                    result = ((Holder)SERVICE_OUTPUT_PARSER.parse(extractorChatResponse, Holder.class)).value();
                } else {
                    result = SERVICE_OUTPUT_PARSER.parse(extractorChatResponse, parameterContext.clazz());
                }
                return new ResolutionDone(result);
            }
        }

        record Holder(String value) {

        }
    }

    public record AgentSpecification(String name, String description) {

    }

    public static class UnknownAgentException extends RuntimeException {

    }

    public static class UnknownToolException extends RuntimeException {

        public UnknownToolException(String message) {
            super(message);
        }
    }
}
