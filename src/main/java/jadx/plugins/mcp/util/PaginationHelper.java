package jadx.plugins.mcp.util;

import java.util.Collections;
import java.util.List;

/**
 * Helpers for paginating lists and slicing source text within token budgets.
 */
public final class PaginationHelper {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 500;
	public static final int DEFAULT_MAX_CHARS = 32_000;

	private PaginationHelper() {
	}

	public static <T> PageResult<T> paginate(List<T> items, int offset, int limit) {
		return paginate(items, offset, limit, MAX_LIMIT);
	}

	public static <T> PageResult<T> paginate(List<T> items, int offset, int limit, int maxLimit) {
		int safeOffset = Math.max(0, offset);
		int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, maxLimit);
		int total = items == null ? 0 : items.size();
		if (safeOffset >= total) {
			return new PageResult<>(Collections.emptyList(), safeOffset, safeLimit, total, false, -1);
		}
		int toIndex = Math.min(total, safeOffset + safeLimit);
		boolean hasMore = toIndex < total;
		int nextOffset = hasMore ? toIndex : -1;
		return new PageResult<>(items.subList(safeOffset, toIndex), safeOffset, safeLimit, total, hasMore, nextOffset);
	}

	public static LineSliceResult sliceLines(String text, Integer startLine, Integer endLine, int maxChars) {
		if (text == null || text.isEmpty()) {
			return new LineSliceResult("", 1, 0, 0, false);
		}
		String[] lines = text.split("\r?\n", -1);
		int totalLines = lines.length;
		int from = startLine == null ? 1 : Math.max(1, startLine);
		int to = endLine == null ? totalLines : Math.min(totalLines, endLine);
		if (from > to || from > totalLines) {
			return new LineSliceResult("", from, to, totalLines, false);
		}

		int charBudget = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
		StringBuilder sb = new StringBuilder();
		boolean truncated = false;
		int lastIncludedLine = from - 1;
		for (int i = from; i <= to; i++) {
			String line = lines[i - 1];
			int addedLen = line.length() + (sb.length() > 0 ? 1 : 0);
			if (sb.length() > 0 && sb.length() + addedLen > charBudget) {
				truncated = true;
				break;
			}
			if (sb.length() > 0) {
				sb.append('\n');
			}
			if (line.length() > charBudget) {
				sb.append(line, 0, charBudget);
				lastIncludedLine = i;
				truncated = true;
				break;
			}
			sb.append(line);
			lastIncludedLine = i;
		}
		return new LineSliceResult(sb.toString(), from, lastIncludedLine, totalLines, truncated);
	}

	public static final class PageResult<T> {
		private final List<T> items;
		private final int offset;
		private final int limit;
		private final int total;
		private final boolean hasMore;
		private final int nextOffset;

		public PageResult(List<T> items, int offset, int limit, int total, boolean hasMore, int nextOffset) {
			this.items = items;
			this.offset = offset;
			this.limit = limit;
			this.total = total;
			this.hasMore = hasMore;
			this.nextOffset = nextOffset;
		}

		public List<T> getItems() {
			return items;
		}

		public int getOffset() {
			return offset;
		}

		public int getLimit() {
			return limit;
		}

		public int getTotal() {
			return total;
		}

		public boolean isHasMore() {
			return hasMore;
		}

		public int getNextOffset() {
			return nextOffset;
		}
	}

	public static final class LineSliceResult {
		private final String content;
		private final int startLine;
		private final int endLine;
		private final int totalLines;
		private final boolean truncated;

		public LineSliceResult(String content, int startLine, int endLine, int totalLines, boolean truncated) {
			this.content = content;
			this.startLine = startLine;
			this.endLine = endLine;
			this.totalLines = totalLines;
			this.truncated = truncated;
		}

		public String getContent() {
			return content;
		}

		public int getStartLine() {
			return startLine;
		}

		public int getEndLine() {
			return endLine;
		}

		public int getTotalLines() {
			return totalLines;
		}

		public boolean isTruncated() {
			return truncated;
		}
	}
}
