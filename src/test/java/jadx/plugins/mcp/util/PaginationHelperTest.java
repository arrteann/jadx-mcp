package jadx.plugins.mcp.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import jadx.plugins.mcp.util.PaginationHelper.LineSliceResult;
import jadx.plugins.mcp.util.PaginationHelper.PageResult;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationHelperTest {

	@Test
	void paginateHandlesMiddleAndLastPages() {
		List<String> items = Arrays.asList("a", "b", "c", "d", "e");

		PageResult<String> first = PaginationHelper.paginate(items, 0, 2);
		assertThat(first.getItems()).containsExactly("a", "b");
		assertThat(first.getTotal()).isEqualTo(5);
		assertThat(first.isHasMore()).isTrue();
		assertThat(first.getNextOffset()).isEqualTo(2);

		PageResult<String> last = PaginationHelper.paginate(items, 4, 2);
		assertThat(last.getItems()).containsExactly("e");
		assertThat(last.isHasMore()).isFalse();
		assertThat(last.getNextOffset()).isEqualTo(-1);
	}

	@Test
	void paginateHandlesEmptyAndOutOfRangeOffset() {
		PageResult<String> empty = PaginationHelper.paginate(Collections.emptyList(), 0, 10);
		assertThat(empty.getItems()).isEmpty();
		assertThat(empty.getTotal()).isZero();
		assertThat(empty.isHasMore()).isFalse();

		PageResult<String> beyond = PaginationHelper.paginate(Arrays.asList("x", "y"), 5, 10);
		assertThat(beyond.getItems()).isEmpty();
		assertThat(beyond.getTotal()).isEqualTo(2);
		assertThat(beyond.isHasMore()).isFalse();
	}

	@Test
	void paginateClampsMaxLimitAndNegativeOffset() {
		List<String> items = Arrays.asList("1", "2", "3");
		PageResult<String> page = PaginationHelper.paginate(items, -5, 1000, 2);
		assertThat(page.getOffset()).isZero();
		assertThat(page.getLimit()).isEqualTo(2);
		assertThat(page.getItems()).containsExactly("1", "2");
		assertThat(page.isHasMore()).isTrue();
	}

	@Test
	void sliceLinesExtractsRequestedRange() {
		String code = "line1\nline2\nline3\nline4";
		LineSliceResult slice = PaginationHelper.sliceLines(code, 2, 3, 1000);
		assertThat(slice.getContent()).isEqualTo("line2\nline3");
		assertThat(slice.getStartLine()).isEqualTo(2);
		assertThat(slice.getEndLine()).isEqualTo(3);
		assertThat(slice.getTotalLines()).isEqualTo(4);
		assertThat(slice.isTruncated()).isFalse();
	}

	@Test
	void sliceLinesTruncatesWhenExceedingCharBudget() {
		String code = "12345\n67890\nabcde";
		LineSliceResult slice = PaginationHelper.sliceLines(code, 1, 3, 11);
		assertThat(slice.getContent()).isEqualTo("12345\n67890");
		assertThat(slice.getEndLine()).isEqualTo(2);
		assertThat(slice.isTruncated()).isTrue();
	}
}
