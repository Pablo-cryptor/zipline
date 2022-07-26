import Foundation
import UIKit
import shared

class EmojiSearchViewController : UIViewController {
    override func viewDidLoad() {
        let it = PresentersEmojiSearchZipline(httpClient: HTTPClient(), hostApi: IosHostApi())
        
    }
}
