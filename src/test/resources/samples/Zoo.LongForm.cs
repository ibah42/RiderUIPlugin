// ================================================================================================
// Zoo.LongForm.cs -- the same zoo of shapes as Zoo.EveryShape.cs, grown up.
//
// Zoo.EveryShape.cs is wide and short: it holds one of everything, and almost every block
// in it is three lines long. That makes it a good test of the CLASSIFIER and a useless one
// for every rule that asks "is this block long enough to say something about" -- the label
// thresholds, the [N] repeated after a closing brace, the block-span marker. None of those
// fire in it at all.
//
// This file is the other half. Same vocabulary, but sized on purpose: about half of its
// blocks cross the threshold that makes the closing brace speak, and the rest sit just
// under it. Several sit exactly ON it, one line either side, which is where an off-by-one
// would hide.
//
// Sizes are generated, not typed -- see repro/gen_longform.py in the plugin's scratch
// history. The bodies are filler with a shape: control flow, loops and switches, so the
// scanner has something to walk past rather than a wall of blank lines.
// ================================================================================================

#pragma warning disable CS0169, CS0649, CS1591, CS8321, CS0067

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AllmanView.Zoo.LongForm
{
    // ======================================================================================
    // functions: 29 and 30 lines straddle the label, 59 and 60 the span
    // ======================================================================================

    public sealed class FunctionThresholds
    {
        private readonly List<int> source = new List<int>();

        public int JustUnderTheLabel(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        public int ExactlyAtTheLabel(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;

            return total;
        }

        public int JustOverTheLabel(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int JustUnderTheSpan(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("SpanUnder");
            log.Append(total);
            seen["SpanUnder"] = total;
            total = Math.Max(total, source.Count);

            return total;
        }

        public int ExactlyAtTheSpan(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("SpanExact");
            log.Append(total);
            seen["SpanExact"] = total;
            total = Math.Max(total, source.Count);
            total = Math.Min(total, source.Count * 2);

            return total;
        }

        public int WellOverTheSpan(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("SpanOver");

            return total;
        }

        public int Tiny(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();


            return total;
        }
    }

    // ======================================================================================
    // types: 49 and 50 lines straddle the label, 99 and 100 the span
    // ======================================================================================

    public sealed class TypeJustUnderTheLabel
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;

            return total;
        }

        private int tulabel_pad0;
        private int tulabel_pad1;
    }

    public sealed class TypeExactlyAtTheLabel
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;

            return total;
        }

        private int talabel_pad0;
        private int talabel_pad1;
        private int talabel_pad2;
    }

    public sealed class TypeJustUnderTheSpan
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;

            return total;
        }

        public int Step2(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("TUSpanS2");
            log.Append(total);

            return total;
        }

        private int tuspan_pad0;
    }

    public sealed class TypeExactlyAtTheSpan
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;

            return total;
        }

        public int Step2(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("TASpanS2");
            log.Append(total);

            return total;
        }

        private int taspan_pad0;
        private int taspan_pad1;
    }

    // ======================================================================================
    // siblings: 14 and 15 lines straddle the repeated [N]
    // ======================================================================================

    public sealed class SiblingHost
    {
        public sealed class SiblingUnder
        {
            private readonly List<int> source = new List<int>();

            public int Work(List<int> source)
            {
                var total = 0;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();


                return total;
            }
        }

        public sealed class SiblingExact
        {
            private readonly List<int> source = new List<int>();

            public int Work(List<int> source)
            {
                var total = 0;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                total += source.Count;

                return total;
            }
        }

        public sealed class SiblingOver
        {
            private readonly List<int> source = new List<int>();

            public int Work(List<int> source)
            {
                var total = 0;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                if (total > source.Count)
                {
                    total -= source.Count;
                }
                else
                {
                    total += source.Count;
                }

                foreach (var item in source)
                {
                    total += item;
                    buffer.Add(item);
                }

                for (var index = 0; index < source.Count; index++)
                {
                    total += index;
                }

                total += source.Count;
                total -= source.Count / 4;
                log.Append("OverW");
                log.Append(total);
                seen["OverW"] = total;
                total = Math.Max(total, source.Count);

                return total;
            }
        }

        private readonly List<int> source = new List<int>();
    }

    // ======================================================================================
    // properties: accessors either side of the 40-line span
    // ======================================================================================

    public sealed class PropertyZoo
    {
        private int field;

        public int Small
        {
            get
            {
                var total = field;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                total += source.Count;
                total -= source.Count / 4;
                log.Append("SmallGet");

                return total;
            }

            set
            {
                var total = value;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                total += source.Count;
                total -= source.Count / 4;
                log.Append("SmallSet");
                log.Append(total);
                field = total;
            }
        }

        public int Large
        {
            get
            {
                var total = field;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                if (total > source.Count)
                {
                    total -= source.Count;
                }
                else
                {
                    total += source.Count;
                }

                foreach (var item in source)
                {
                    total += item;
                    buffer.Add(item);
                }

                total += source.Count;
                total -= source.Count / 4;

                return total;
            }

            set
            {
                var total = value;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                if (total > source.Count)
                {
                    total -= source.Count;
                }
                else
                {
                    total += source.Count;
                }

                foreach (var item in source)
                {
                    total += item;
                    buffer.Add(item);
                }

                total += source.Count;
                field = total;
            }
        }

        public int Auto { get; set; }
    }

    // ======================================================================================
    // three levels of nesting, every level long enough to be named
    // ======================================================================================

    public sealed class Level1
    {
        private readonly List<int> source = new List<int>();

        public sealed class Level2
        {
            private readonly List<int> source = new List<int>();

            public sealed class Level3
            {
                private readonly List<int> source = new List<int>();

                public int Deepest(List<int> source)
                {
                    var total = 0;
                    var log = new StringBuilder();
                    var seen = new Dictionary<string, int>();
                    var buffer = new List<int>();

                    if (total > source.Count)
                    {
                        total -= source.Count;
                    }
                    else
                    {
                        total += source.Count;
                    }

                    foreach (var item in source)
                    {
                        total += item;
                        buffer.Add(item);
                    }

                    for (var index = 0; index < source.Count; index++)
                    {
                        total += index;
                    }

                    switch (total % 3)
                    {
                        case 0:
                            total++;
                            break;
                        default:
                            total--;
                            break;
                    }

                    try
                    {
                        total += source.Count;
                    }
                    catch (InvalidOperationException)
                    {
                        total = 0;
                    }

                    while (total > 1000)
                    {
                        total /= 2;
                    }

                    total += source.Count;
                    total -= source.Count / 4;

                    return total;
                }
            }

            public int AtLevel2(List<int> source)
            {
                var total = 0;
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                if (total > source.Count)
                {
                    total -= source.Count;
                }
                else
                {
                    total += source.Count;
                }

                foreach (var item in source)
                {
                    total += item;
                    buffer.Add(item);
                }

                for (var index = 0; index < source.Count; index++)
                {
                    total += index;
                }

                total += source.Count;
                total -= source.Count / 4;
                log.Append("Mid");
                log.Append(total);
                seen["Mid"] = total;
                total = Math.Max(total, source.Count);

                return total;
            }
        }

        public int AtLevel1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Top");
            log.Append(total);
            seen["Top"] = total;
            total = Math.Max(total, source.Count);

            return total;
        }
    }

    // ======================================================================================
    // constructors, destructor, operators -- all grown past the label
    // ======================================================================================

    public sealed class MemberZoo
    {
        private readonly List<int> source = new List<int>();
        private int field;

        static MemberZoo()
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Static");
            log.Append(total);
        }

        public MemberZoo(int seed)
            : this(seed, 0)
        {
            var total = seed;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Ctor");
            log.Append(total);
            seen["Ctor"] = total;
            total = Math.Max(total, source.Count);
        }

        public MemberZoo(int seed, int offset)
        {
            field = seed + offset;
        }

        ~MemberZoo()
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Dtor");
            log.Append(total);
            seen["Dtor"] = total;
            total = Math.Max(total, source.Count);
        }

        public static MemberZoo operator +(MemberZoo left, MemberZoo right)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var source = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Op");
            log.Append(total);
            seen["Op"] = total;

            return left;
        }

        public static bool operator ==(MemberZoo left, MemberZoo right)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var source = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Op");

            return total > 0;
        }

        public static bool operator !=(MemberZoo left, MemberZoo right)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var source = new List<int>();


            return total > 0;
        }

        public static implicit operator int(MemberZoo value)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var source = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }


            return total;
        }

        public override bool Equals(object other)
        {
            return false;
        }

        public override int GetHashCode()
        {
            return field;
        }
    }

    // ======================================================================================
    // lambdas long enough to be named at their closing brace
    // ======================================================================================

    public sealed class LambdaZoo
    {
        private readonly List<int> source = new List<int>();

        public IEnumerable<int> Passed(List<int> source)
        {
            var total = 0;

            var mapped = source.Select(value =>
            {
                var log = new StringBuilder();
                var seen = new Dictionary<string, int>();
                var buffer = new List<int>();

                if (total > source.Count)
                {
                    total -= source.Count;
                }
                else
                {
                    total += source.Count;
                }

                foreach (var item in source)
                {
                    total += item;
                    buffer.Add(item);
                }

                for (var index = 0; index < source.Count; index++)
                {
                    total += index;
                }

                total += source.Count;
                total -= source.Count / 4;
                log.Append("Lam");
                log.Append(total);
                seen["Lam"] = total;
                total = Math.Max(total, source.Count);

                return value + total;
            });

            return mapped;
        }
    }

    // ======================================================================================
    // records and structs, grown
    // ======================================================================================

    public record LongRecord(int X, int Y)
    {
        private readonly List<int> source;

        public int Measure(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("LongRecordM");
            log.Append(total);
            seen["LongRecordM"] = total;
            total = Math.Max(total, source.Count);
            total = Math.Min(total, source.Count * 2);
            buffer.Add(total);

            return total;
        }
    }

    public readonly record struct LongCoord(int X, int Y)
    {
        private readonly List<int> source;

        public int Measure(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }


            return total;
        }
    }

    public struct LongPoint
    {
        private readonly List<int> source;

        public int Measure(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }


            return total;
        }
    }

    // ======================================================================================
    // the bulk: everything here is far bigger than Zoo.EveryShape, half of it still quiet
    // ======================================================================================

    public sealed class QuietType00
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet00S0");
            log.Append(total);
            seen["Quiet00S0"] = total;
            total = Math.Max(total, source.Count);
            total = Math.Min(total, source.Count * 2);
            buffer.Add(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet00S1");

            return total;
        }

    }

    public sealed class QuietType01
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }


            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet01S1");
            log.Append(total);

            return total;
        }

    }

    public sealed class QuietType02
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet02S1");
            log.Append(total);
            seen["Quiet02S1"] = total;

            return total;
        }

    }

    public sealed class QuietType03
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }


            return total;
        }

    }

    public sealed class QuietType04
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet04S0");

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet04S1");

            return total;
        }

    }

    public sealed class QuietType05
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet05S0");
            log.Append(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet05S1");
            log.Append(total);

            return total;
        }

    }

    public sealed class QuietType06
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet06S0");
            log.Append(total);
            seen["Quiet06S0"] = total;
            total = Math.Max(total, source.Count);
            total = Math.Min(total, source.Count * 2);
            buffer.Add(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet06S1");
            log.Append(total);
            seen["Quiet06S1"] = total;

            return total;
        }

    }

    public sealed class QuietType07
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }


            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }


            return total;
        }

    }

    public sealed class QuietType08
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet08S1");

            return total;
        }

    }

    public sealed class QuietType09
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet09S1");
            log.Append(total);

            return total;
        }

    }

    public sealed class QuietType10
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet10S0");

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet10S1");
            log.Append(total);
            seen["Quiet10S1"] = total;

            return total;
        }

    }

    public sealed class QuietType11
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet11S0");
            log.Append(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }


            return total;
        }

    }

    public sealed class QuietType12
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet12S0");
            log.Append(total);
            seen["Quiet12S0"] = total;
            total = Math.Max(total, source.Count);
            total = Math.Min(total, source.Count * 2);
            buffer.Add(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet12S1");

            return total;
        }

    }

    public sealed class QuietType13
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }


            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Quiet13S1");
            log.Append(total);

            return total;
        }

    }

    public sealed class HeavyQuietType00
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet00_pad0;
        private int heavyquiet00_pad1;
        private int heavyquiet00_pad2;
        private int heavyquiet00_pad3;
        private int heavyquiet00_pad4;
        private int heavyquiet00_pad5;
        private int heavyquiet00_pad6;
        private int heavyquiet00_pad7;
        private int heavyquiet00_pad8;
        private int heavyquiet00_pad9;
        private int heavyquiet00_pad10;
        private int heavyquiet00_pad11;
        private int heavyquiet00_pad12;
    }

    public sealed class HeavyQuietType01
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet01_pad0;
        private int heavyquiet01_pad1;
        private int heavyquiet01_pad2;
        private int heavyquiet01_pad3;
        private int heavyquiet01_pad4;
        private int heavyquiet01_pad5;
        private int heavyquiet01_pad6;
        private int heavyquiet01_pad7;
        private int heavyquiet01_pad8;
        private int heavyquiet01_pad9;
        private int heavyquiet01_pad10;
        private int heavyquiet01_pad11;
        private int heavyquiet01_pad12;
    }

    public sealed class HeavyQuietType02
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet02_pad0;
        private int heavyquiet02_pad1;
        private int heavyquiet02_pad2;
        private int heavyquiet02_pad3;
        private int heavyquiet02_pad4;
        private int heavyquiet02_pad5;
        private int heavyquiet02_pad6;
        private int heavyquiet02_pad7;
        private int heavyquiet02_pad8;
        private int heavyquiet02_pad9;
        private int heavyquiet02_pad10;
        private int heavyquiet02_pad11;
        private int heavyquiet02_pad12;
    }

    public sealed class HeavyQuietType03
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet03_pad0;
        private int heavyquiet03_pad1;
        private int heavyquiet03_pad2;
        private int heavyquiet03_pad3;
        private int heavyquiet03_pad4;
        private int heavyquiet03_pad5;
        private int heavyquiet03_pad6;
        private int heavyquiet03_pad7;
        private int heavyquiet03_pad8;
        private int heavyquiet03_pad9;
        private int heavyquiet03_pad10;
        private int heavyquiet03_pad11;
        private int heavyquiet03_pad12;
    }

    public sealed class HeavyQuietType04
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet04_pad0;
        private int heavyquiet04_pad1;
        private int heavyquiet04_pad2;
        private int heavyquiet04_pad3;
        private int heavyquiet04_pad4;
        private int heavyquiet04_pad5;
        private int heavyquiet04_pad6;
        private int heavyquiet04_pad7;
        private int heavyquiet04_pad8;
        private int heavyquiet04_pad9;
        private int heavyquiet04_pad10;
        private int heavyquiet04_pad11;
        private int heavyquiet04_pad12;
    }

    public sealed class HeavyQuietType05
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet05_pad0;
        private int heavyquiet05_pad1;
        private int heavyquiet05_pad2;
        private int heavyquiet05_pad3;
        private int heavyquiet05_pad4;
        private int heavyquiet05_pad5;
        private int heavyquiet05_pad6;
        private int heavyquiet05_pad7;
        private int heavyquiet05_pad8;
        private int heavyquiet05_pad9;
        private int heavyquiet05_pad10;
        private int heavyquiet05_pad11;
        private int heavyquiet05_pad12;
    }

    public sealed class HeavyQuietType06
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet06_pad0;
        private int heavyquiet06_pad1;
        private int heavyquiet06_pad2;
        private int heavyquiet06_pad3;
        private int heavyquiet06_pad4;
        private int heavyquiet06_pad5;
        private int heavyquiet06_pad6;
        private int heavyquiet06_pad7;
        private int heavyquiet06_pad8;
        private int heavyquiet06_pad9;
        private int heavyquiet06_pad10;
        private int heavyquiet06_pad11;
        private int heavyquiet06_pad12;
    }

    public sealed class HeavyQuietType07
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int heavyquiet07_pad0;
        private int heavyquiet07_pad1;
        private int heavyquiet07_pad2;
        private int heavyquiet07_pad3;
        private int heavyquiet07_pad4;
        private int heavyquiet07_pad5;
        private int heavyquiet07_pad6;
        private int heavyquiet07_pad7;
        private int heavyquiet07_pad8;
        private int heavyquiet07_pad9;
        private int heavyquiet07_pad10;
        private int heavyquiet07_pad11;
        private int heavyquiet07_pad12;
    }

    public sealed class NamedType00
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Named00S0");
            log.Append(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;

            return total;
        }

        private int named00_pad0;
        private int named00_pad1;
    }

    public sealed class NamedType01
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Named01S0");
            log.Append(total);
            seen["Named01S0"] = total;
            total = Math.Max(total, source.Count);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        private int named01_pad0;
        private int named01_pad1;
    }

    public sealed class NamedType02
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Named02S0");
            log.Append(total);
            seen["Named02S0"] = total;
            total = Math.Max(total, source.Count);
            total = Math.Min(total, source.Count * 2);
            buffer.Add(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Named02S1");

            return total;
        }

        private int named02_pad0;
        private int named02_pad1;
    }

    public sealed class NamedType03
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }


            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;

            return total;
        }

        private int named03_pad0;
        private int named03_pad1;
        private int named03_pad2;
        private int named03_pad3;
        private int named03_pad4;
    }

    public sealed class NamedType04
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        private int named04_pad0;
        private int named04_pad1;
        private int named04_pad2;
        private int named04_pad3;
        private int named04_pad4;
    }

    public sealed class NamedType05
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Named05S0");
            log.Append(total);

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Named05S1");

            return total;
        }

        private int named05_pad0;
        private int named05_pad1;
        private int named05_pad2;
        private int named05_pad3;
        private int named05_pad4;
        private int named05_pad5;
        private int named05_pad6;
        private int named05_pad7;
        private int named05_pad8;
        private int named05_pad9;
        private int named05_pad10;
        private int named05_pad11;
        private int named05_pad12;
        private int named05_pad13;
        private int named05_pad14;
    }

    public sealed class SpanningType00
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step2(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

    }

    public sealed class SpanningType01
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Span01S0");
            log.Append(total);
            seen["Span01S0"] = total;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step2(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Span01S2");

            return total;
        }

    }

    public sealed class SpanningType02
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Span02S0");

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Span02S1");

            return total;
        }

        public int Step2(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Span02S2");
            log.Append(total);

            return total;
        }

        private int span02_pad0;
        private int span02_pad1;
        private int span02_pad2;
        private int span02_pad3;
        private int span02_pad4;
        private int span02_pad5;
        private int span02_pad6;
        private int span02_pad7;
        private int span02_pad8;
        private int span02_pad9;
    }

    public sealed class SpanningType03
    {
        private readonly List<int> source = new List<int>();

        public int Step0(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Step1(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("Span03S1");
            log.Append(total);
            seen["Span03S1"] = total;
            total = Math.Max(total, source.Count);
            total = Math.Min(total, source.Count * 2);
            buffer.Add(total);
            log.Append(seen.Count);

            return total;
        }

        public int Step2(List<int> source)
        {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }


            return total;
        }

        private int span03_pad0;
        private int span03_pad1;
        private int span03_pad2;
        private int span03_pad3;
        private int span03_pad4;
        private int span03_pad5;
        private int span03_pad6;
        private int span03_pad7;
        private int span03_pad8;
        private int span03_pad9;
        private int span03_pad10;
        private int span03_pad11;
        private int span03_pad12;
        private int span03_pad13;
        private int span03_pad14;
        private int span03_pad15;
        private int span03_pad16;
        private int span03_pad17;
        private int span03_pad18;
        private int span03_pad19;
        private int span03_pad20;
        private int span03_pad21;
        private int span03_pad22;
        private int span03_pad23;
        private int span03_pad24;
    }

}

// ================================================================================================
// The same again in K&R, so the move mechanic has long blocks to draw too.
// ================================================================================================

namespace AllmanView.Zoo.LongForm.Kandr
{
    public sealed class Hanging {
        private readonly List<int> source = new List<int>();

        public int Quiet(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;

            return total;
        }

        public int Modest(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            total += source.Count;

            return total;
        }

        public int Named(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("KNamed");
            log.Append(total);
            seen["KNamed"] = total;
            total = Math.Max(total, source.Count);

            return total;
        }

        public int Longer(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("KLonger");

            return total;
        }

        public int Spanned(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("KSpanned");
            log.Append(total);
            seen["KSpanned"] = total;

            return total;
        }

        public int Wider(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("KWider");
            log.Append(total);
            seen["KWider"] = total;
            total = Math.Max(total, source.Count);

            return total;
        }

        public int Widest(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            foreach (var item in source)
            {
                total += item;
                buffer.Add(item);
            }

            for (var index = 0; index < source.Count; index++)
            {
                total += index;
            }

            switch (total % 3)
            {
                case 0:
                    total++;
                    break;
                default:
                    total--;
                    break;
            }

            try
            {
                total += source.Count;
            }
            catch (InvalidOperationException)
            {
                total = 0;
            }

            while (total > 1000)
            {
                total /= 2;
            }


            return total;
        }

        public int Calm(List<int> source) {
            var total = 0;
            var log = new StringBuilder();
            var seen = new Dictionary<string, int>();
            var buffer = new List<int>();

            if (total > source.Count)
            {
                total -= source.Count;
            }
            else
            {
                total += source.Count;
            }

            total += source.Count;
            total -= source.Count / 4;
            log.Append("KCalm");
            log.Append(total);
            seen["KCalm"] = total;

            return total;
        }

    }
}

