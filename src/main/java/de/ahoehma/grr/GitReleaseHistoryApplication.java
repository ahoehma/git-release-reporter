package de.ahoehma.grr;

import static de.ahoehma.grr.jgit.LogCommandBuilder.builder;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.StreamSupport.stream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author Andreas Höhmann
 * @since 0.0.1
 */
@SpringBootApplication
public class GitReleaseHistoryApplication {

	@RestController
	public static class GitReleaseHistoryController {

		static class GitReleaseHistoryEntry {
			@JsonProperty
			Long id;
			@JsonProperty
			String hash;
			@JsonProperty
			String authorEmailAdress;
			@JsonProperty
			Date authorDate;
			@JsonProperty
			String shortMessage;
			@JsonProperty
			@JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
			List<String> files;
			@JsonProperty
			@JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
			Set<String> modules;
		}

		private @Value("${git-repo-path:.}") String gitRepoPath;
		private Repository repository;

		@PostConstruct
		void initRepository() throws IOException {
			repository = new FileRepositoryBuilder()
					.setGitDir(new File(gitRepoPath, "/.git"))
					.readEnvironment()
					.findGitDir()
					.setMustExist(true)
					.build();
		}

		static void collectFilesAndModules(final Repository repository, final Git git, final String oldCommit,
				final String newCommit, final GitReleaseHistoryEntry commit, final boolean showFiles,
				final boolean showModules) throws GitAPIException, IOException {
			final AbstractTreeIterator oldTree = prepareTreeParser(repository, oldCommit);
			final AbstractTreeIterator newTree = prepareTreeParser(repository, newCommit);
			final List<DiffEntry> diffs = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
			final List<String> paths = new ArrayList<>();
			final Set<String> modules = new LinkedHashSet<>();
			for (final DiffEntry diff : diffs) {
				String path;
				switch (diff.getChangeType()) {
				case DELETE:
					path = diff.getOldPath();
					break;
				default:
					path = diff.getNewPath();
					break;
				}
				if (showFiles) {
					paths.add(path);
				}
				if (showModules) {
					if (Paths.get(path).getParent() == null) {
						// change in root
						modules.add("<ROOT>");
					} else {
						modules.add(Paths.get(path).getName(0).toString());
					}
				}
			}
			if (showFiles) {
				commit.files = paths;
			}
			if (showModules) {
				commit.modules = modules;
			}
		}

		static AbstractTreeIterator prepareTreeParser(final Repository repository, final String objectId)
				throws IOException {
			try (RevWalk walk = new RevWalk(repository)) {
				final ObjectId resolve = repository.resolve(objectId);
				if (resolve==null) {
					return null;
				}
				final RevCommit commit = walk.parseCommit(resolve);
				final RevTree tree = walk.parseTree(commit.getTree().getId());
				final CanonicalTreeParser treeParser = new CanonicalTreeParser();
				try (ObjectReader reader = repository.newObjectReader()) {
					treeParser.reset(reader, tree.getId());
				}
				walk.dispose();
				return treeParser;
			}
		}
		
		static Predicate<? super RevCommit> ignoreMessage(final String ignoreMessage) {
			return revCommit -> ignoreMessage.trim().isEmpty() ? true : !revCommit.getShortMessage().contains(ignoreMessage);
		}
		
		static GitReleaseHistoryEntry createDTO(final Git git, final Repository repository, final RevCommit revCommit,
				final Supplier<Long> idProvider, final boolean showFiles, final boolean showModules) {
			final PersonIdent authorIdent = revCommit.getAuthorIdent();
			final String hash = revCommit.getName();
			final GitReleaseHistoryEntry commit = new GitReleaseHistoryEntry();
			commit.id = idProvider.get();
			commit.hash = hash;
			if (authorIdent!=null) {
				commit.authorEmailAdress = authorIdent.getEmailAddress();
				commit.authorDate = authorIdent.getWhen();
			}
			commit.shortMessage = revCommit.getShortMessage();
			if (showFiles || showModules) {
				try {
					collectFilesAndModules(repository, git, hash + "^", hash, commit, showFiles, showModules);
				} catch (GitAPIException | IOException e) {
					// TODO error handling :)
				}
			}
			return commit;
		}
		
		static LogCommand log(final int limit, final String from, final String to, final String paths,
				final boolean ignoreMerges, final Repository repository, final Git git) throws IOException {
			return builder(git, repository).withMaxCount(limit).withRange(from, to).withPaths(paths)
					.ignoreMerges(ignoreMerges).build();
		}

		@GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
		public Stream<GitReleaseHistoryEntry> getCommits(
				@RequestParam(required = false, defaultValue = "10") final int limit,
				@RequestParam(required = false, defaultValue = "") final String from,
				@RequestParam(required = false, defaultValue = "") final String to,
				@RequestParam(required = false, defaultValue = "") final String paths,
				@RequestParam(required = false, defaultValue = "") final String ignoreMessage,
				@RequestParam(required = false, defaultValue = "true") final boolean showFiles,
				@RequestParam(required = false, defaultValue = "true") final boolean showModules,
				@RequestParam(required = false, defaultValue = "false") final boolean ignoreMerges) throws Exception {
			try (Git git = new Git(repository)) {
				final AtomicLong counter = new AtomicLong();
				final Iterable<RevCommit> history = log(limit, from, to, paths, ignoreMerges, repository, git).call();
				final Spliterator<RevCommit> spliterator = spliteratorUnknownSize(history.iterator(),Spliterator.ORDERED);
				final Stream<RevCommit> stream = stream(spliterator, false);
				return stream
						.filter(ignoreMessage(ignoreMessage))
						.map(revCommit -> createDTO(git, repository, revCommit, counter::incrementAndGet, showFiles, showModules));
			}
		}

		@GetMapping(value = "/tags", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
		public Stream<String> getReleases() throws Exception {
			try (Git git = new Git(repository)) {
				return stream(git.tagList().call().spliterator(), false).map(Ref::getName);
			}
		}
	}

	public static void main(final String[] args) {
		SpringApplication.run(GitReleaseHistoryApplication.class, args);
	}

}
