package br.com.caelum.brutal.controllers;

import static br.com.caelum.brutal.util.TagsSplitter.splitTags;

import java.util.Arrays;
import java.util.List;

import br.com.caelum.brutal.auth.FacebookAuthService;
import br.com.caelum.brutal.auth.LoggedAccess;
import br.com.caelum.brutal.auth.rules.AuthorizationSystem;
import br.com.caelum.brutal.auth.rules.PermissionRulesConstants;
import br.com.caelum.brutal.dao.QuestionDAO;
import br.com.caelum.brutal.dao.TagDAO;
import br.com.caelum.brutal.dao.VoteDAO;
import br.com.caelum.brutal.factory.MessageFactory;
import br.com.caelum.brutal.model.LoggedUser;
import br.com.caelum.brutal.model.Question;
import br.com.caelum.brutal.model.QuestionInformation;
import br.com.caelum.brutal.model.QuestionViewCounter;
import br.com.caelum.brutal.model.Tag;
import br.com.caelum.brutal.model.UpdateStatus;
import br.com.caelum.brutal.model.User;
import br.com.caelum.brutal.reputation.rules.ReputationEvent;
import br.com.caelum.brutal.reputation.rules.ReputationEvents;
import br.com.caelum.brutal.validators.TagsValidator;
import br.com.caelum.brutal.vraptor.Linker;
import br.com.caelum.vraptor.Get;
import br.com.caelum.vraptor.Post;
import br.com.caelum.vraptor.Resource;
import br.com.caelum.vraptor.Result;
import br.com.caelum.vraptor.Validator;
import br.com.caelum.vraptor.util.hibernate.extra.Load;
import br.com.caelum.vraptor.view.Results;

@Resource
public class QuestionController {

	private final Result result;
	private final QuestionDAO questions;
	private final TagDAO tags;
	private final VoteDAO votes;
	private final LoggedUser currentUser;
	private final TagsValidator tagsValidator;
	private final MessageFactory messageFactory;
	private final AuthorizationSystem authorizationSystem;
	private final Validator validator;
	private final FacebookAuthService facebook;
	private final QuestionViewCounter viewCounter;
	private Linker linker;

	public QuestionController(Result result, QuestionDAO questionDAO, TagDAO tags, 
			VoteDAO votes, LoggedUser currentUser, FacebookAuthService facebook,
			TagsValidator tagsValidator, MessageFactory messageFactory,
			AuthorizationSystem authorizationSystem, Validator validator, QuestionViewCounter viewCounter, Linker linker) {
		this.result = result;
		this.questions = questionDAO;
		this.tags = tags;
		this.votes = votes;
		this.currentUser = currentUser;
		this.facebook = facebook;
		this.tagsValidator = tagsValidator;
		this.messageFactory = messageFactory;
		this.authorizationSystem = authorizationSystem;
		this.validator = validator;
		this.viewCounter = viewCounter;
		this.linker = linker;
	}

	@Get("/perguntar")
	@LoggedAccess
	public void questionForm() {
	}
	
	@Get("/pergunta/editar/{questionId}")
	public void questionEditForm(Long questionId) {
		Question question = questions.getById(questionId);
		authorizationSystem.canEdit(question, PermissionRulesConstants.EDIT_QUESTION);
		
		result.include("question",  questions.getById(questionId));
	}

	@Post("/pergunta/editar/{id}")
	public void edit(Long id, String title, String description, String tagNames, String comment) {
		Question original = questions.getById(id);
		authorizationSystem.canEdit(original, PermissionRulesConstants.EDIT_QUESTION);
		
		List<String> splitedTags = splitTags(tagNames);
		List<Tag> loadedTags = tags.findAllWithoutRepeat(splitedTags);
		QuestionInformation information = new QuestionInformation(title, description, this.currentUser, loadedTags, comment);
		UpdateStatus status = original.updateWith(information);
		
		result.include("editComment", comment);
		result.include("question", original);
		validator.validate(information);
		validate(loadedTags, splitedTags);
		validator.onErrorUse(Results.page()).of(this.getClass()).questionEditForm(id);
		
		questions.save(original);
		result.include("messages",
				Arrays.asList(messageFactory.build("confirmation", status.getMessage())));
		result.redirectTo(this).showQuestion(original, original.getSluggedTitle());
	}
	
	@Get("/{question.id:[0-9]+}-{sluggedTitle}")
	public void showQuestion(@Load Question question, String sluggedTitle){
		if (!question.getSluggedTitle().equals(sluggedTitle)) {
			result.redirectTo(this).showQuestion(question,
					question.getSluggedTitle());
			return;
		}
		viewCounter.ping(question);
		User voteAuthor = currentUser.getCurrent();
		result.include("currentVote", votes.previousVoteFor(question.getId(), voteAuthor, Question.class));
		result.include("answers", votes.previousVotesForAnswers(question, voteAuthor));
		result.include("commentsWithVotes", votes.previousVotesForComments(question, voteAuthor));
		result.include("questionTags", question.getInformation().getTags());
		result.include("question", question);
		linker.linkTo(this).showQuestion(question, sluggedTitle);
		result.include("facebookUrl", facebook.getOauthUrl(linker.get()));
	}

	@Post("/perguntar")
	@LoggedAccess
	@ReputationEvent(ReputationEvents.NEW_QUESTION)
	public void newQuestion(String title, String description, String tagNames) {
		List<String> splitedTags = splitTags(tagNames);
		List<Tag> foundTags = tags.findAllWithoutRepeat(splitedTags);
		QuestionInformation information = new QuestionInformation(title, description, currentUser, foundTags, "new question");
		Question question = new Question(information, currentUser.getCurrent());
		
		result.include("question", question);
		validator.validate(information);

		if(!validator.hasErrors() && validate(foundTags, splitedTags)){
			questions.save(question);
			result.redirectTo(this).showQuestion(question, question.getSluggedTitle());
		}
	
		validator.onErrorRedirectTo(this).questionForm();
	}

	

	private boolean validate(List<Tag> foundTags, List<String> splitedTags) {
		return tagsValidator.validate(foundTags, splitedTags);
	}
}
