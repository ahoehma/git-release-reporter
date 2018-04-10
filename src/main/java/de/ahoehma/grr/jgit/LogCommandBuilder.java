package de.ahoehma.grr.jgit;

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.filter.AndRevFilter;
import org.eclipse.jgit.revwalk.filter.MaxCountRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;

/**
 * @author Andreas Höhmann
 * @since 0.0.1
 */
public final class LogCommandBuilder {

  private final Git git;
  private final Repository repo;

  private int maxCount;
  private boolean ignoreMerges;
  private String paths;
  private String from;
  private String to;

  private LogCommandBuilder(final Git git, final Repository repo) {
    this.git = git;
    this.repo = repo;
  }

  public static LogCommandBuilder builder(final Git git, final Repository repo) {
    return new LogCommandBuilder(git, repo);
  }

  static ObjectId getActualRefObjectId(final Repository repository, final Ref ref) {
    final Ref repoPeeled = repository.peel(ref);
    if (repoPeeled.getPeeledObjectId() != null) {
      return repoPeeled.getPeeledObjectId();
    }
    return ref.getObjectId();
  }

  public LogCommand build() throws IOException {
    final LogCommand log = git.log();
    if (maxCount > -1) {
      if (ignoreMerges) {
        // LogCommand doesn't support max-count AND rev-filter together
        log.setRevFilter(AndRevFilter.create(MaxCountRevFilter.create(maxCount), RevFilter.NO_MERGES));
      } else {
        log.setMaxCount(maxCount);
      }
    }
    if (paths != null && !paths.isEmpty()) {
      Arrays.stream(paths.split(",")).forEach(log::addPath);
    }
    final Ref refFrom = repo.findRef(from);
    final Ref refTo = repo.findRef(to);
    if (refFrom != null) {
      if (refTo != null) {
        log.addRange(getActualRefObjectId(repo, refFrom), getActualRefObjectId(repo, refTo));
      } else {
        log.add(getActualRefObjectId(repo, refFrom));
      }
    }
    return log;
  }

  public LogCommandBuilder ignoreMerges(final boolean ignoreMerges) {
    this.ignoreMerges = ignoreMerges;
    return this;
  }

  public LogCommandBuilder withMaxCount(final int maxCount) {
    this.maxCount = maxCount;
    return this;
  }

  public LogCommandBuilder withPaths(final String paths) {
    this.paths = paths;
    return this;
  }

  public LogCommandBuilder withRange(final String from, final String to) throws IOException {
    this.from = from;
    this.to = to;
    return this;
  }
}