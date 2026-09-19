// Objective-C rides on the C++ dialect and adds two shapes of its own: the `- (void)name:`
// method header and the `^{ }` block literal.

#import "HTAClient.h"

static NSString *const kDefaultName = @"default";

@implementation HTAClient

- (instancetype)initWithName:(NSString *)name
{
    self = [super init];
    if (self)
    {
        _name = [name copy];
    }
    return self;
}

+ (instancetype)shared
{
    static HTAClient *instance = nil;
    return instance;
}

- (void)fetchWithCompletion:(void (^)(NSData *data, NSError *error))completion
{
    [UIView animateWithDuration:0.3 animations:^{
        self.alpha = 1.0;
    } completion:^(BOOL finished) {
        completion(nil, nil);
    }];
}

- (NSString *)nameForIndex:(NSInteger)index andFlag:(BOOL)flag
{
    if (flag)
    {
        return @"{ not a block }";
    }
    return kDefaultName;
}

- (void)each:(void (^)(NSInteger idx))handler
{
    for (NSInteger i = 0; i < 3; i++)
    {
        handler(i);
    }
}

@end

static void HTALogEverything(NSArray *items)
{
    for (id item in items)
    {
        NSLog(@"%@", item);
    }
}
