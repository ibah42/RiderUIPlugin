// =================================================================================================
// A hand-written fixture, not vendored from anywhere: one file holding every shape the scanner is
// supposed to recognise, and a fair number it is supposed to ignore.
//
// The vendored UniTask samples beside this one are real code, which is their whole value -- but
// real code only contains what its authors happened to write. Three of the four are pure Allman,
// so they exercise no phantom line at all. This file is the opposite: it is deliberately
// exhaustive and deliberately mixed, Allman in one half and K&R in the other.
//
// It is not meant to do anything. It is meant to be READ by the golden file next to it: every
// block here should turn into one line there, saying what it is. A line that says something else
// is the bug.
// =================================================================================================

#pragma warning disable CS0169, CS0649, CS1591, CS8321, CS0067

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace AllmanView.Zoo
{
    // =============================================================== nested namespaces

    namespace Inner
    {
        // A namespace inside a namespace: nested, and the first of two siblings.
        public sealed class InsideInner
        {
            public void Work()
            {
            }
        }
    }

    namespace Sibling
    {
        public sealed class InsideSibling
        {
            public void Work()
            {
            }
        }
    }

    // =============================================================== every type keyword

    public interface IShape
    {
        int Sides { get; }

        // A default interface method: an interface with a real body in it.
        string Describe()
        {
            return "a shape with " + Sides + " sides";
        }
    }

    public enum Corner
    {
        None,
        Rounded,
        Sharp,
    }

    public struct Point2
    {
        public int X;
        public int Y;
    }

    public readonly struct Immutable
    {
        public readonly int Value;
    }

    // No block at all: a positional record ending in a semicolon declares nothing to accent.
    public record Bare(string First, string Last);

    public record Named(string First, string Last)
    {
        public string Full
        {
            get
            {
                return First + " " + Last;
            }
        }
    }

    public record class Handle(int Id)
    {
        public void Touch()
        {
        }
    }

    public readonly record struct Coord(int X, int Y)
    {
        public int Sum()
        {
            return X + Y;
        }
    }

    public record struct Mutable(int Count)
    {
        public void Bump()
        {
            Count++;
        }
    }

    public static class Helpers
    {
        public static int Twice(int value)
        {
            return value * 2;
        }
    }

    public abstract class Abstract
    {
        public abstract void Must();

        protected virtual void May()
        {
        }
    }

    public partial class Split
    {
        public void First()
        {
        }
    }

    public partial class Split
    {
        public void Second()
        {
        }
    }

    // =============================================================== base-type lists

    public class SingleBase : Abstract
    {
        public override void Must()
        {
        }
    }

    public class LeadingColon
        : Abstract
    {
        public override void Must()
        {
        }
    }

    // The shape that used to make the class vanish: only the first continuation line starts
    // with the colon, the rest start with an ordinary identifier.
    public class WrappedList
        : Abstract,
          IShape,
          IDisposable
    {
        public int Sides
        {
            get
            {
                return 0;
            }
        }

        public override void Must()
        {
        }

        public void Dispose()
        {
        }
    }

    public class TrailingComma : Abstract,
                                 IShape
    {
        public int Sides
        {
            get
            {
                return 1;
            }
        }

        public override void Must()
        {
        }
    }

    // A generic base broken inside its own angle brackets.
    public class WrappedGeneric : Dictionary<string,
        List<int>>
    {
    }

    public class Constrained<T, TOther>
        : Abstract,
          IShape
        where T : class, new()
        where TOther : struct
    {
        public int Sides
        {
            get
            {
                return 2;
            }
        }

        public override void Must()
        {
        }
    }

    // =============================================================== three levels of nesting

    public class Level1
    {
        public class Level2
        {
            public class Level3
            {
                public void Deepest()
                {
                    // A local function inside a method inside three types.
                    void Helper()
                    {
                        void Deeper()
                        {
                        }

                        Deeper();
                    }

                    Helper();
                }
            }

            public class AlsoLevel3
            {
            }
        }

        public struct AlsoLevel2
        {
        }
    }

    // =============================================================== members

    public class MemberZoo : IShape, IDisposable
    {
        private readonly int seed;
        private int cached;

        public event Action Changed;

        static MemberZoo()
        {
        }

        public MemberZoo()
            : this(0)
        {
        }

        public MemberZoo(int seed)
        {
            this.seed = seed;
        }

        ~MemberZoo()
        {
        }

        // An auto-property: one block, no accessor bodies.
        public int Sides { get; private set; }

        public string Name { get; init; }

        // An expression-bodied property has no block of its own.
        public int Doubled => seed * 2;

        public int Cached
        {
            get
            {
                return cached;
            }
            set
            {
                cached = value;
            }
        }

        public static int Shared
        {
            get
            {
                return 0;
            }
        }

        // An indexer is a property that takes arguments, and its brackets may hold a default.
        public int this[int index, int fallback = 0]
        {
            get
            {
                return index + fallback;
            }
            set
            {
                cached = value;
            }
        }

        public void Dispose()
        {
        }

        // =========================================================== operators

        public static MemberZoo operator +(MemberZoo left, MemberZoo right)
        {
            return left;
        }

        public static MemberZoo operator -(MemberZoo only)
        {
            return only;
        }

        public static bool operator ==(MemberZoo left, MemberZoo right)
        {
            return ReferenceEquals(left, right);
        }

        public static bool operator !=(MemberZoo left, MemberZoo right)
        {
            return !(left == right);
        }

        public static bool operator true(MemberZoo value)
        {
            return true;
        }

        public static bool operator false(MemberZoo value)
        {
            return false;
        }

        public static implicit operator int(MemberZoo value)
        {
            return value.seed;
        }

        public static explicit operator MemberZoo(int value)
        {
            return new MemberZoo(value);
        }

        // A conversion whose target is written out in full, which makes for a long label.
        public static implicit operator System.Collections.Generic.List<int>(MemberZoo value)
        {
            return new List<int>();
        }

        public override bool Equals(object other)
        {
            return false;
        }

        public override int GetHashCode()
        {
            return seed;
        }
    }

    // =============================================================== function shapes

    public class FunctionZoo
    {
        public void Plain()
        {
        }

        public static void Static()
        {
        }

        public async Task<int> AsyncWork(CancellationToken token)
        {
            await Task.Yield();
            return 0;
        }

        public void Defaults(
            int retries = 3,
            string tag = "",
            string closer = ")",
            string brace = "{",
            char marker = '}')
        {
        }

        // The tuple return type: the first `(` belongs to the type, not to the parameter list.
        public (int Index, string Label) Tuple(short token)
        {
            return (0, "");
        }

        public (int, string)? NullableTuple(int id)
        {
            return null;
        }

        public void Ref(ref int byRef, out int result, in int readOnly)
        {
            result = byRef + readOnly;
        }

        public void Params(params int[] values)
        {
        }

        public void Generic<T>(T value)
            where T : IComparable<T>
        {
        }

        // `record` is contextual, so this is a parameter name and not a type declaration.
        public void Contextual(object record, object value)
        {
        }

        // An explicit interface implementation has a dotted name.
        void IDisposableLike.Release()
        {
        }

        // Expression-bodied: no block, nothing to accent.
        public int Short() => 1;

        public void Locals()
        {
            int Adder(int a, int b)
            {
                return a + b;
            }

            Adder(1, 2);
        }
    }

    public interface IDisposableLike
    {
        void Release();
    }

    // =============================================================== lambdas

    public class LambdaZoo
    {
        // Assigned to a field: the name is borrowed from the assignment target.
        private static readonly Func<int> Producer = () =>
        {
            return 0;
        };

        private static readonly Action Wrapped = new Action(() =>
        {
        });

        public void Passed(IEnumerable<int> values)
        {
            // Passed to a call: the name is borrowed from the call.
            var mapped = values.Select(value =>
            {
                return value * 2;
            });

            // A lambda inside a lambda inside a call.
            var nested = values.Select(value =>
            {
                return values.Where(other =>
                {
                    return other > value;
                });
            });

            // An anonymous method rather than a lambda.
            Action<int> anonymous = delegate(int value)
            {
            };

            // Two lambdas as two arguments of one call. The first borrows the call's name; the
            // second cannot, and the golden records it with no name at all. Its header starts
            // after the `}` that closed the first one, so the slice the classifier gets is
            // `, () =>` -- the call that is still open began on a line the header window does
            // not reach. A known limit of reading one line back, not a misreading of this one.
            Register(() =>
            {
            }, () =>
            {
            });
        }

        private void Register(Action first, Action second)
        {
        }
    }

    // =============================================================== not declarations

    public class ControlFlow
    {
        public int Everything(int input, IEnumerable<int> values)
        {
            if (input > 0)
            {
                input++;
            }
            else if (input < 0)
            {
                input--;
            }
            else
            {
                input = 0;
            }

            for (int index = 0; index < 10; index++)
            {
            }

            foreach (var record in values)
            {
            }

            while (input > 100)
            {
                input--;
            }

            do
            {
                input++;
            }
            while (input < 0);

            switch (input)
            {
                case 0:
                {
                    break;
                }
                default:
                {
                    break;
                }
            }

            try
            {
            }
            catch (InvalidOperationException)
            {
            }
            catch (Exception ex) when (ex.Message.Length > 0)
            {
            }
            finally
            {
            }

            using (var disposable = new MemberZoo())
            {
            }

            lock (this)
            {
            }

            checked
            {
                input++;
            }

            unchecked
            {
                input++;
            }

            // A bare block, owned by nothing.
            {
                input++;
            }

            return input;
        }

        public void Initializers()
        {
            // An object initializer is not a declaration, however much it looks like one.
            var single = new Point2
            {
                X = 1,
                Y = 2,
            };

            var collection = new List<int>
            {
                1,
                2,
                3,
            };

            // Nested initializers, each ending its lines with a comma.
            var nested = new List<Point2>
            {
                new Point2
                {
                    X = 1,
                    Y = 2,
                },
                new Point2
                {
                    X = 3,
                    Y = 4,
                },
            };

            var array = new[]
            {
                1,
                2,
            };

            var dictionary = new Dictionary<string, int>
            {
                { "one", 1 },
                { "two", 2 },
            };
        }
    }

    // =============================================================== literals and comments

    public class LiteralTraps
    {
        public void Strings()
        {
            var plain = "a { and a } inside a string";
            var keyword = "class Fake { }";
            var verbatim = @"a verbatim { string } with ""quotes"" in it";
            var interpolated = $"an interpolated {plain} with {{escaped}} braces";
            var both = $@"both at once: {plain} and { } and ""quotes""";
            var raw = """
                a raw string { with braces } and "quotes"
                """;
            var openBrace = '{';
            var closeBrace = '}';
            var quote = '"';
            var escaped = '\'';
        }

        /*
         * A block comment with a { brace } in it, and the word class,
         * neither of which opens anything.
         */
        public void Commented()
        {
            // A line comment with { and } and the word struct in it.
            var value = 0; // trailing comment with a }
        }
    }
}

// =============================================================== a second top-level namespace

namespace AllmanView.Zoo.Kandr
{
    // Everything below is written K&R, so the plugin has phantom lines to draw. The Allman half
    // above exercises the classifier; this half exercises the move mechanic that stands beside it.

    public class Hanging {
        private int state;

        public Hanging() {
            state = 0;
        }

        public int Work(int input) {
            if (input > 0) {
                input++;
            } else if (input < 0) {
                input--;
            } else {
                input = 0;
            }

            for (int index = 0; index < 4; index++) {
                input += index;
            }

            // An inline block on one line.
            if (input > 100) { input = 100; }

            // A statement with no block at all.
            if (input < 0) return 0;

            foreach (var item in new[] { 1, 2, 3 }) {
                input += item;
            }

            return input;
        }

        // A multi-line signature whose brace hangs off the last line.
        public int Wrapped(
            int first,
            int second,
            int third) {
            return first + second + third;
        }

        public int Property {
            get {
                return state;
            }
            set {
                state = value;
            }
        }

        public static Hanging operator +(Hanging left, Hanging right) {
            return left;
        }

        public void Lambdas(IEnumerable<int> values) {
            var mapped = values.Select(value => {
                return value * 2;
            });
        }
    }

    public struct HangingStruct {
        public int X;

        public int Doubled() {
            return X * 2;
        }
    }

    // A constructor initializer on its own line, in K&R.
    public class WithBaseCall : Hanging {
        public WithBaseCall(int value)
            : base() {
            }
    }
}

// =============================================================== preprocessor

namespace AllmanView.Zoo.Conditional
{
    public class Preprocessed
    {
#if UNITY_EDITOR
        public void EditorOnly()
        {
        }
#else
        public void RuntimeOnly()
        {
        }
#endif

        #region Grouped

        public void InsideRegion()
        {
        }

        #endregion

        [Obsolete("use something else { }")]
        [System.Diagnostics.Conditional("DEBUG")]
        public void Attributed()
        {
        }
    }
}
